package org.alien4cloud.plugin.consulpublisher.modifier;

import org.alien4cloud.alm.deployment.configuration.flow.TopologyModifierSupport;
import org.alien4cloud.tosca.model.CSARDependency;
import org.alien4cloud.tosca.model.templates.Topology;

import javax.annotation.Resource;

import static org.alien4cloud.plugin.consulpublisher.csar.Version.CONSULPUBLISHER_CSAR_VERSION;

public abstract class AbstractConsulModifier extends TopologyModifierSupport {

    protected static final String RUNNER_NAME ="consul_plugin_runner_node_name";

    @Resource
    protected ConsulPublisherConfiguration configuration;

    protected final String getCsarVersion(Topology topology) {
        return getCsarVersion(topology,"org.alien4cloud.consulpublisher");
    }

    protected final  String getCsarVersion(Topology topology,String archiveName) {
        for (CSARDependency dep : topology.getDependencies()) {
            if (dep.getName().equals(archiveName)) {
                return dep.getVersion();
            }
        }
        return CONSULPUBLISHER_CSAR_VERSION;
    }
}
