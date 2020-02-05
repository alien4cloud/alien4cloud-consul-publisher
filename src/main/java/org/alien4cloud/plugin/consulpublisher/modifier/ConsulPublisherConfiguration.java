package org.alien4cloud.plugin.consulpublisher.modifier;

import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@EnableConfigurationProperties
@ConfigurationProperties(prefix = "consul")
public class ConsulPublisherConfiguration {

    private String url;

    private String certificate;

    private String key;

    private String command = "echo Working";
}