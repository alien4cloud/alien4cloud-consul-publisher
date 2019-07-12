package org.alien4cloud.plugin.consulpublisher;

import alien4cloud.plugin.archives.AbstractArchiveProviderPlugin;
import org.springframework.stereotype.Component;

@Component("consulpublisher-archives-provider")
public class ConsulPublisherArchivesProvider extends AbstractArchiveProviderPlugin {
    @Override
    protected String[] getArchivesPaths() {
        return new String[] { "csar" };
    }
}
