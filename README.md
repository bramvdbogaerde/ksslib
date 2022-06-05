# Scala Kubernetes Config Generator 

This repository contains a simple opinionated library for generating Kubernetes configurations in order to deploy simple applications.

## Motivation

The complexity of Kubernetes is already a huge stepping stone for those who would like to learn and use Kubernetes. In addition to that, in order to deploy an application, a lot of configuration is required, which becomes rather repetitive and time consuming after a while.

Some solutions have been proposed to make this process easier, such as [Helm Charts](https://helm.sh/), and [Kustomize](https://kustomize.io/). However, these approaches are still YAML based. 
While they do try to make use of some programming language concepts such as abstraction and code re-use through templates and patches, I always found them fundamentally lacking. 

The idea of this library is to use an actual programming language (in this case Scala) to configure Kubernetes applications, which benefits from abstraction through functional programming language concepts, as well as OO concepts.

At the moment, the library is tailored to my personal needs, as such some of the implementations and configuration generating functions are rather opinionated. I currently have no plans to change this.

## Usage 

### Installation

This library is (not yet) published. However, you can publish it to the local Ivy repository in order to use it yourself. In order to do this, you can run `sbt publishLocal` which triggers a build of the library and publishes it to the local Ivy repository.

### Creating a configuration file

The intended usage is through [Ammonite](https://ammonite.io/) such that you can write down your Kubernetes configuration using a Scala script.

An example of a Kubernetes configuration file is given below:

```scala
import $ivy.`ksslib::ksslib:0.1` 
import ksslib.prelude.*

object WhoamiDeployment extends ExposedDeployment:
  override def name: String = "whoami"
  override def mapping = Map(
    "web" -> "whoami.example.com"
  )
  override def labels = Map("app" -> "whoami")
  override def containers: List[Container] = 
    List(Container(
      name = "whoami-container",
      image = "containous/whoami",
      ports = List(Port(name = "web", port = 80))
    ))

given IngressConfig with   
  val certResolver = "default"
   
Kubectl.applyConf(Config.aggregate(List(
  WhoamiDeployment,
  Ingress.expose(WhoamiDeployment)
)))
```

The example above creates a deployment that consists of a single `whoami` pod. 
Then it exposes port `80` of that pod as a service in the Kubernetes cluster.
Finally, it exposes that service to the external world through a Traefik IngressRoute.

Note that this might not be the configuration of your own Kubernetes cluster, and the `Ingress.expose`  function might not generate an ideal configuration for you. In that case, I invite you to extend one of the classes in the library and make your own variations. However, **I will not accept any pull requests that generate slightly different configurations**.
