package org.alien4cloud.plugin.consulpublisher.modifier;

import alien4cloud.paas.wf.validation.WorkflowValidator;
import alien4cloud.tosca.context.ToscaContextual;
import lombok.extern.java.Log;
import lombok.extern.slf4j.Slf4j;
import org.alien4cloud.alm.deployment.configuration.flow.FlowExecutionContext;
import org.alien4cloud.tosca.model.Csar;
import org.alien4cloud.tosca.model.definitions.ScalarPropertyValue;
import org.alien4cloud.tosca.model.templates.NodeTemplate;
import org.alien4cloud.tosca.model.templates.Topology;
import org.alien4cloud.tosca.normative.constants.NormativeComputeConstants;
import org.alien4cloud.tosca.normative.constants.NormativeRelationshipConstants;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.logging.Level;

import static org.alien4cloud.plugin.consulpublisher.policies.ConsulPublisherPolicyConstants.SHELL_RUNNER;

@Slf4j
@Component("consul-notifier")
public class ConsulNotifierModifier extends AbstractConsulModifier {

    @Override
    @ToscaContextual
    public void process(Topology topology, FlowExecutionContext context) {
        log.info("Processing topology {}",topology.getId());

        try {
            WorkflowValidator.disableValidationThreadLocal.set(true);
            doProcess(topology, context);
        } catch (Exception e) {
            context.getLog().error("Couldn't process consul publisher modifier");
            log.warn("Couldn't process consul publisher modifier", e);
        } finally {
            WorkflowValidator.disableValidationThreadLocal.remove();
        }
    }

    private void doProcess(Topology topology, FlowExecutionContext context) {
        Csar csar = new Csar(topology.getArchiveName(), topology.getArchiveVersion());

        /* get initial topology */
        Topology init_topology = (Topology)context.getExecutionCache().get(FlowExecutionContext.INITIAL_TOPOLOGY);

        NodeTemplate compute = addNodeTemplate(csar,topology, "ShellCompute", NormativeComputeConstants.COMPUTE_TYPE, getCsarVersion(init_topology,"tosca-normative-types"));
        NodeTemplate runner = addNodeTemplate(csar, topology, "ShellRunner", SHELL_RUNNER,getCsarVersion(init_topology));

        addRelationshipTemplate(csar, topology, runner, compute.getName(), NormativeRelationshipConstants.HOSTED_ON, "host", "host");

        setNodePropertyPathValue(csar, topology, runner, "command", new ScalarPropertyValue(configuration.getCommand()));

        context.getExecutionCache().put(RUNNER_NAME, runner.getName());
    }

}