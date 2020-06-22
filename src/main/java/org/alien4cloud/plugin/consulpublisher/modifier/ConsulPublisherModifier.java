package org.alien4cloud.plugin.consulpublisher.modifier;

import alien4cloud.common.MetaPropertiesService;
import alien4cloud.model.common.Tag;
import alien4cloud.model.common.MetaPropertyTarget;
import alien4cloud.paas.wf.TopologyContext;
import alien4cloud.paas.wf.WorkflowSimplifyService;
import alien4cloud.paas.wf.WorkflowsBuilderService;
import alien4cloud.paas.wf.validation.WorkflowValidator;
import alien4cloud.tosca.context.ToscaContextual;
import alien4cloud.tosca.context.ToscaContext;
import alien4cloud.tosca.parser.ToscaParser;
import static alien4cloud.utils.AlienUtils.safe;
import alien4cloud.utils.PropertyUtil;
import alien4cloud.utils.YamlParserUtil;

import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.alien4cloud.alm.deployment.configuration.flow.EnvironmentContext;
import org.alien4cloud.alm.deployment.configuration.flow.FlowExecutionContext;
import org.alien4cloud.alm.deployment.configuration.flow.TopologyModifierSupport;

import org.alien4cloud.plugin.consulpublisher.policies.ConsulPublisherPolicyConstants;
import org.alien4cloud.tosca.model.CSARDependency;
import org.alien4cloud.tosca.model.definitions.AbstractPropertyValue;
import org.alien4cloud.tosca.model.definitions.ScalarPropertyValue;
import org.alien4cloud.tosca.model.templates.Capability;
import org.alien4cloud.tosca.model.templates.NodeTemplate;
import org.alien4cloud.tosca.model.templates.PolicyTemplate;
import org.alien4cloud.tosca.model.templates.RelationshipTemplate;
import org.alien4cloud.tosca.model.templates.Topology;
import org.alien4cloud.tosca.model.types.AbstractToscaType;
import org.alien4cloud.tosca.normative.constants.NormativeComputeConstants;
import org.alien4cloud.tosca.normative.constants.NormativeNodeTypesConstants;
import org.alien4cloud.tosca.normative.constants.NormativeRelationshipConstants;
import org.alien4cloud.tosca.normative.constants.NormativeTypesConstant;
import org.alien4cloud.tosca.utils.TopologyNavigationUtil;

import org.apache.commons.lang.StringUtils;
import org.springframework.stereotype.Component;

import lombok.extern.java.Log;
import lombok.Getter;
import lombok.Setter;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static alien4cloud.utils.AlienUtils.safe;

import static org.alien4cloud.plugin.kubernetes.modifier.KubernetesAdapterModifier.A4C_KUBERNETES_ADAPTER_MODIFIER_TAG_REPLACEMENT_NODE_FOR;
import static org.alien4cloud.plugin.kubernetes.modifier.KubernetesAdapterModifier.NAMESPACE_RESOURCE_NAME;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_SERVICE_RESOURCE;

import static org.alien4cloud.plugin.consulpublisher.policies.ConsulPublisherPolicyConstants.*;
import static org.alien4cloud.plugin.consulpublisher.csar.Version.CONSULPUBLISHER_CSAR_VERSION;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import javax.annotation.Resource;
import javax.inject.Inject;

@Slf4j
@Component("consul-publisher")
public class ConsulPublisherModifier extends AbstractConsulModifier {

    @Inject
    private WorkflowSimplifyService workflowSimplifyService;
    @Inject
    private WorkflowsBuilderService workflowBuilderService;

    @Resource
    private MetaPropertiesService metaPropertiesService;

    private static final String CUNAME_PROP = "Cas d'usage";

    private HashMap<String, String> serviceTypes = new HashMap<String,String>() {{
       put (CONSULPUBLISHER_POLICY1, "ihm");
       put (CONSULPUBLISHER_POLICY2, "api");
    }};

    private final ObjectMapper mapper = new ObjectMapper();
 
    @Override
    @ToscaContextual
    public void process(Topology topology, FlowExecutionContext context) {
        log.info("Processing topology {}" ,topology.getId());
        try {
            WorkflowValidator.disableValidationThreadLocal.set(true);
            doProcess(topology, context);
        } catch (Exception e) {
            log.warn ("Couldn't process consul publisher modifier", e);
        } finally {
            WorkflowValidator.disableValidationThreadLocal.remove();
            log.debug("Finished processing topology " + topology.getId());
        }
    }

    private boolean doProcess(Topology topology, FlowExecutionContext context) {
        List<NodeTemplate> publishers = Lists.newArrayList();

        boolean updated = false;

        /* get initial topology */
        Topology init_topology = (Topology)context.getExecutionCache().get(FlowExecutionContext.INITIAL_TOPOLOGY);

        String appQualifiedName = null;
        List<Tag> topoTags = init_topology.getTags();
        for (Tag tag: safe(topoTags)) {
           if (tag.getName().equals("qualifiedName")) {
              appQualifiedName = tag.getValue();
           }
        }
        if (appQualifiedName == null) {
           log.warn ("Cannot find app qualified name");
        }

        /* get all ConsulPublisher policies on initial topology */
        Set<PolicyTemplate> policies = TopologyNavigationUtil.getPoliciesOfType(init_topology, CONSULPUBLISHER_POLICY, true);
        for (PolicyTemplate policy : policies) {
           int id = 0;
           log.info("Processing policy {}",policy.getName());

           /* get all target nodes on current policy */
           Set<NodeTemplate> targetedMembers = TopologyNavigationUtil.getTargetedMembers(init_topology, policy);
           for (NodeTemplate node : targetedMembers) {
              log.info("Processing node {}", node.getName());

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
              NodeTemplate csnode = addNodeTemplate(null,topology,nodeName, CONSUL_RUNNER, getCsarVersion(init_topology));

              // Must add an abstract node to invoke Shell
              publishers.add(csnode);

              /* set consul url and optionally key/certificate (file names on orchestrator machine) */
              String consulUrl = configuration.getUrl();
              if ((consulUrl == null) || consulUrl.equals("")) {
                 consulUrl = "http://localhost:8500";
              }
              setNodePropertyPathValue(null,topology,csnode,"url", new ScalarPropertyValue(consulUrl));
              if ( (configuration.getCertificate() != null) && (configuration.getKey() != null) ) {
                 setNodePropertyPathValue(null,topology,csnode,"certificate", new ScalarPropertyValue(configuration.getCertificate()));
                 setNodePropertyPathValue(null,topology,csnode,"key", new ScalarPropertyValue(configuration.getKey()));
              }

              /* data to be published into consul */
              ConsulData data = new ConsulData();

              data.setAppQualifiedName(appQualifiedName);

              /* service name and url from K8S plugin node */
              String serviceName;
              String url = null;
              String upstreamUrl = null;

              if (kubeNode != null) {
                  serviceName = PropertyUtil.getScalarValue(kubeNode.getProperties().get("service_name"));
                  url = PropertyUtil.getScalarValue(kubeNode.getProperties().get("url"));
                  upstreamUrl = PropertyUtil.getScalarValue(kubeNode.getProperties().get("cluster_url"));
              } else {
                  serviceName = PropertyUtil.getScalarValue(node.getProperties().get("service_name"));
                  if (serviceName == null) {
                      serviceName = node.getName();
                 }
              }

              /* get "Cas d'usage" from initial topology meta property */
              String cuname = null;
              String cuNameMetaPropertyKey = this.metaPropertiesService.getMetapropertykeyByName(CUNAME_PROP, MetaPropertyTarget.APPLICATION);

              if (cuNameMetaPropertyKey != null) {
                 Optional<EnvironmentContext> ec = context.getEnvironmentContext();
                 if (ec.isPresent() && cuNameMetaPropertyKey != null) {
                    EnvironmentContext env = ec.get();
                    Map<String, String> metaProperties = safe(env.getApplication().getMetaProperties());
                    String sCuname = metaProperties.get(cuNameMetaPropertyKey);
                    if (StringUtils.isNotEmpty(sCuname)) {
                        cuname = sCuname;
                    }
                }
              }
              
              if (cuname == null) {
                 log.warn( "Can not find {}", CUNAME_PROP);
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

              /* get port and url_path from capability properties of service */
              String port = "";
              String url_path = null;
              Capability endpoint = safe(node.getCapabilities()).get("service_endpoint");
              if (endpoint != null) {
                 port = PropertyUtil.getScalarValue(safe(endpoint.getProperties()).get("port"));
                 if (StringUtils.isNotEmpty(port)) {
                    port = ":" + port;
                 } else {
                    port = "";
                 }
                 url_path = PropertyUtil.getScalarValue(safe(endpoint.getProperties()).get("url_path"));
              }

              RelationshipTemplate relation = TopologyNavigationUtil.getRelationshipFromType(node, NormativeRelationshipConstants.CONNECTS_TO);
              NodeTemplate module = init_topology.getNodeTemplates().get(relation.getTarget());

              String qualifiedName = "not_set";
              List<Tag> tags = module.getTags();
              for (Tag tag: safe(tags)) {
                 if (tag.getName().equals("qualifiedName")) {
                    qualifiedName = tag.getValue();
                 }
              }
              if (qualifiedName.equals("not_set")) {
                 log.warn ("Cannot find qualified name for " + node.getName());
              }


              String zone = "";
              /* get zone from namespace resource */
              NodeTemplate kubeNS = topology.getNodeTemplates().get((String)context.getExecutionCache().get(NAMESPACE_RESOURCE_NAME));
              if (kubeNS != null) {
                 try {
                    ObjectNode spec = (ObjectNode) mapper.readTree(PropertyUtil.getScalarValue(kubeNS.getProperties().get("resource_spec")));
                    zone = spec.with("metadata").with("labels").get("ns-zone-de-sensibilite").textValue();
                 } catch(Exception e) {
                    log.info("Can't find ns-zone-de-sensibilite");
                 }
              } else {
                 log.info ("No namespace resource");
              }

              String name = cuname + "/" + qualifiedName;
              setNodePropertyPathValue(null,topology,csnode,"name", new ScalarPropertyValue(name));

              /* other info got from policy or generated */
              Map<String,AbstractPropertyValue> polProps = policy.getProperties();
              String description = PropertyUtil.getScalarValue(polProps.get("description"));
              data.setName(description);
              data.setAdmin(Boolean.valueOf(PropertyUtil.getScalarValue(polProps.get("admin"))));
              data.setQualifiedName(qualifiedName);
              data.setDescription(description);
              data.setLogo("/logo.png");
              data.setLogo(PropertyUtil.getScalarValue(polProps.get("logo")));
              data.setDeploymentDate ( (new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")).format(new Date()).toString() );
              data.setType (serviceTypes.get(policy.getType()));
              data.setUrl(url);
              //data.setUpstreamUrl("http://" + serviceName + "." + namespace + ".svc.cluster.local" + port);
              data.setUpstreamUrl(upstreamUrl);
              data.setZone(zone);
              data.setContextPath(url_path);
              data.setNamespace (namespace);

              try {
                 setNodePropertyPathValue(null,topology,csnode,"data", new ScalarPropertyValue(mapper.writeValueAsString(data)));
              } catch (Exception e) {
                 log.warn("Couldn't set data", e);
              }

              if (kubeNode == null) {
                 log.warn("Can not find ServiceResource node for {}" , node.getName());
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
              updated = true;
           }
        }

        String runnerNodeName = (String) context.getExecutionCache().get(RUNNER_NAME);

        if (publishers.size() > 0) {
            if (runnerNodeName == null)  {
                log.warn("No Shell Runner in topology - ConsulNotifier may not be declared");
            } else {
                NodeTemplate runnerNode = topology.getNodeTemplates().get(runnerNodeName);

                for (NodeTemplate node : publishers) {
                    addRelationshipTemplate(null,topology, runnerNode,node.getName(),NormativeRelationshipConstants.DEPENDS_ON, "dependency","feature");
                }
                updated = true;
            }
        } else if (runnerNodeName != null) {
            // No publishers but nodes was added by the consul notifier modifier
            // This node needs to be removed
            NodeTemplate computeNode = TopologyNavigationUtil.getImmediateHostTemplate(topology, topology.getNodeTemplates().get(runnerNodeName));
            removeNode(topology,computeNode);
            updated = true;
        }

        return updated;
    }

    @Getter
    @Setter
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private class ConsulData {
        private String name; 
        private String qualifiedName; 
        private String appQualifiedName; 
        private String contextPath;
        private String description; 
        private String type; 
        private boolean active = true; 
        private String logo; 
        private String deploymentDate; 
        private boolean admin;
        private String url;
        private String upstreamUrl;
        private String zone;
        private String namespace;
    }
}
