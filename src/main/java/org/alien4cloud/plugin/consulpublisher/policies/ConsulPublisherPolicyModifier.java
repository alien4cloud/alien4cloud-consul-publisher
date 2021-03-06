package org.alien4cloud.plugin.consulpublisher.policies;

import alien4cloud.tosca.context.ToscaContextual;
import lombok.extern.slf4j.Slf4j;
import org.alien4cloud.alm.deployment.configuration.flow.FlowExecutionContext;
import org.alien4cloud.alm.deployment.configuration.flow.TopologyModifierSupport;
import org.alien4cloud.tosca.model.templates.Topology;
import org.springframework.stereotype.Component;


@Component("consul-publisher-policy-modifier")
@Slf4j
public class ConsulPublisherPolicyModifier extends TopologyModifierSupport {

    @Override
    @ToscaContextual
    public void process(Topology topology, FlowExecutionContext context) {
        // DO NOTHING
    }
}
