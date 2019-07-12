# Alien4cloud Consul publisher plugin

Plugins that publishes information on deployments to Consul

## Usage
- Apply the Consul Publisher modifier on the orchestrator (should be post-node-match and after Kubernetes Adapter modifier).
- On a topology:
    - Choose one policy among IhmConsulPublisherPolicy and ApiConsulPublisherPolicy
    - Select the target node
    - Fill in the policy properties (technicalName, description, logo)
    - Deploy

## Configuration
In alien4cloud main configuration file, set:

- consul.url : Consul base URL, default is http://localhost:8500 (seen from the orchestrator machine).
- consul.key, consul.certificate : files containing the PEM encoded private key and certificate (located on the orchestrator machine)
