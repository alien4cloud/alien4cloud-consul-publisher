package org.alien4cloud.plugin.consulpublisher.modifier;

import alien4cloud.model.common.Tag;
import alien4cloud.paas.wf.validation.WorkflowValidator;
import alien4cloud.tosca.context.ToscaContextual;
import alien4cloud.utils.PropertyUtil;
import alien4cloud.utils.YamlParserUtil;

import org.alien4cloud.alm.deployment.configuration.flow.FlowExecutionContext;
import org.alien4cloud.alm.deployment.configuration.flow.TopologyModifierSupport;

import org.alien4cloud.tosca.model.definitions.AbstractPropertyValue;
import org.alien4cloud.tosca.model.definitions.ScalarPropertyValue;
import org.alien4cloud.tosca.model.templates.NodeTemplate;
import org.alien4cloud.tosca.model.templates.PolicyTemplate;
import org.alien4cloud.tosca.model.templates.Topology;
import org.alien4cloud.tosca.normative.constants.NormativeRelationshipConstants;
import org.alien4cloud.tosca.utils.TopologyNavigationUtil;
import org.springframework.stereotype.Component;

import lombok.extern.java.Log;
import lombok.Getter;
import lombok.Setter;

import com.fasterxml.jackson.databind.ObjectMapper;

import static alien4cloud.utils.AlienUtils.safe;

import static org.alien4cloud.plugin.kubernetes.modifier.KubernetesAdapterModifier.A4C_KUBERNETES_ADAPTER_MODIFIER_TAG_REPLACEMENT_NODE_FOR;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_SERVICE_RESOURCE;

import static org.alien4cloud.plugin.consulpublisher.policies.ConsulPublisherPolicyConstants.*;
import static org.alien4cloud.plugin.consulpublisher.csar.Version.CONSULPUBLISHER_CSAR_VERSION;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import javax.annotation.Resource;

@Log
@Component("consul-publisher")
public class ConsulPublisherModifier extends TopologyModifierSupport {

    private static final String CUNAME_TAG = "A4C_META_Cas d'usage";

    @Resource
    private ConsulPublisherConfiguration configuration;

    private HashMap<String, String> serviceTypes = new HashMap<String,String>() {{
       put (CONSULPUBLISHER_POLICY1, "ihm");
       put (CONSULPUBLISHER_POLICY2, "api");
    }};

    @Override
    @ToscaContextual
    public void process(Topology topology, FlowExecutionContext context) {
        log.info("Processing topology " + topology.getId());

        try {
            WorkflowValidator.disableValidationThreadLocal.set(true);
            doProcess(topology, context);
        } catch (Exception e) {
            context.getLog().error("Couldn't process consul publisher modifier");
            log.log(Level.WARNING, "Couldn't process consul publisher modifier", e);
        } finally {
            WorkflowValidator.disableValidationThreadLocal.remove();
        }
    }

    private void doProcess(Topology topology, FlowExecutionContext context) {

        /* get initial topology */
        Topology init_topology = (Topology)context.getExecutionCache().get(FlowExecutionContext.INITIAL_TOPOLOGY);

        /* get all ConsulPublisher policies on initial topology */
        Set<PolicyTemplate> policies = TopologyNavigationUtil.getPoliciesOfType(init_topology, CONSULPUBLISHER_POLICY, true);
        for (PolicyTemplate policy : policies) {
           int id = 0;
           log.info("Processing policy " + policy.getName());

           /* get all target nodes on current policy */
           Set<NodeTemplate> targetedMembers = TopologyNavigationUtil.getTargetedMembers(init_topology, policy);
           for (NodeTemplate node : targetedMembers) {
              log.info("Processing node " + node.getName());

              /* get node in final topology corresponding to node in initial topology */
              NodeTemplate kubeNode = null;
              Set<NodeTemplate> kubeNodes = TopologyNavigationUtil.getNodesOfType(topology, K8S_TYPES_SERVICE_RESOURCE, true);

              for (NodeTemplate knode : kubeNodes) {
                 String initialNodeName  = TopologyModifierSupport.getNodeTagValueOrNull(knode, A4C_KUBERNETES_ADAPTER_MODIFIER_TAG_REPLACEMENT_NODE_FOR);
                 if ( (initialNodeName != null) && initialNodeName.equals(node.getName()) ) { 
                    kubeNode = knode;
                    break;
                 }
              }

              /* add a node (to final topology) which will publish info to consul */
              String nodeName = String.format("%s_%d",policy.getName(), id++);
              NodeTemplate csnode = addNodeTemplate(null,topology,nodeName, CONSUL_RUNNER, CONSULPUBLISHER_CSAR_VERSION);

              /* set consul url and optionally key/certificate (file names on orchestrator machine) */
              String url = configuration.getUrl();
              if ((url == null) || url.equals("")) {
                 url = "http://localhost:8500";
              }
              setNodePropertyPathValue(null,topology,csnode,"url", new ScalarPropertyValue(url));
              if ( (configuration.getCertificate() != null) && (configuration.getKey() != null) ) {
                 setNodePropertyPathValue(null,topology,csnode,"certificate", new ScalarPropertyValue(configuration.getCertificate()));
                 setNodePropertyPathValue(null,topology,csnode,"key", new ScalarPropertyValue(configuration.getKey()));
              }

              /* data to be published into consul */
              ConsulData data = new ConsulData();

              /* service name from K8S plugin node */
              String serviceName;

              if (kubeNode != null) {
                  serviceName = PropertyUtil.getScalarValue(kubeNode.getProperties().get("service_name"));
              } else {
                  serviceName = PropertyUtil.getScalarValue(node.getProperties().get("service_name"));
                  if (serviceName == null) {
                      serviceName = node.getName();
                 }
              }

              /* get "Cas d'usage" from initial topology tags */
              List<Tag> tags = init_topology.getTags();
              String cuname = null;
              if (tags != null) {
                 for (Tag tag : tags) {
                    if (tag.getName().equals(CUNAME_TAG)) {
                       cuname = tag.getValue();
                       break;
                    }
                 }
              }
              if (cuname == null) {
                 log.log(Level.WARNING, "Can not find " + CUNAME_TAG);
                 cuname = "default";
              }

              /* get namespace from YAML in kube_config property in kube node  */
              String namespace = null;
              if (kubeNode != null) {
                 String skubeConf = PropertyUtil.getScalarValue(kubeNode.getProperties().get("kube_config"));
                 Map<String, Object> kubeConf = (Map<String, Object>)YamlParserUtil.load(skubeConf);
                 List ct = (List)kubeConf.get("contexts");
                 Map<String,Object> ct0 = (Map<String,Object>)((Map<String,Object>)ct.get(0)).get("context");
                 namespace = (String)ct0.get("namespace");
              }

              setNodePropertyPathValue(null,topology,csnode,"name", new ScalarPropertyValue(cuname + "/" + serviceName));

              /* other info got from policy or generated */
              Map<String,AbstractPropertyValue> polProps = policy.getProperties();
              data.setName(serviceName);
              data.setUc(cuname);
              data.setAdmin(Boolean.valueOf(PropertyUtil.getScalarValue(polProps.get("admin"))));
              data.setQualifiedName(PropertyUtil.getScalarValue(polProps.get("qualifiedName")));
              data.setDescription(PropertyUtil.getScalarValue(polProps.get("description")));
              data.setLogo(PropertyUtil.getScalarValue(polProps.get("logo")));
              data.setDeploymentDate ( (new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")).format(new Date()).toString() );
              data.setType (serviceTypes.get(policy.getType()));
              data.setUrl("http://" + serviceName + "." + namespace + ".svc.cluster.local");

              try {
                 setNodePropertyPathValue(null,topology,csnode,"data", new ScalarPropertyValue((new ObjectMapper()).writeValueAsString(data)));
              } catch (Exception e) {
                 log.log(Level.WARNING, "Couldn't set data", e);
              }

              if (kubeNode == null) {
                 log.log(Level.WARNING, "Can not find ServiceResource node for " + node.getName());
              } else {
                 /* add relationship on target node so as to be run after the node is deployed */
                 addRelationshipTemplate (
                        null,
                        topology,
                        csnode,
                        kubeNode.getName(),
                        NormativeRelationshipConstants.DEPENDS_ON,
                        "dependency",
                        "feature");
              }
           }
        }
    }

    @Getter
    @Setter
    private class ConsulData {
        private String name; 
        private String qualifiedName; 
        private String description; 
        private String type; 
        private boolean active = false; 
        private String logo; 
        private String deploymentDate; 
        private boolean admin;
        private String url;
        private String uc;
    }
}
