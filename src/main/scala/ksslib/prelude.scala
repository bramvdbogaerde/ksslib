package ksslib

package prelude {
  import org.json4s._
  import org.json4s.native.Serialization._
  import org.json4s.native.Serialization

  trait Config:
    def config: List[Map[String, Any]]

  trait SingleConfig extends Config:
    def config: List[Map[String, Any]] =
      List(
        Map(
          "apiVersion" -> apiVersion,
          "kind" -> kind,
          "metadata" -> Map(
            "name" -> name,
            "namespace" -> namespace,
            "labels" -> labels
          ),
          "spec" -> spec
        )
      )

    def namespace: String = "default"
    def name: String
    def labels: Map[String, String] = Map()
    def apiVersion: String
    def kind: String
    def spec: Map[String, Any]

  class AggregateConfig(configs: List[Config]) extends Config:
    override def config: List[Map[String, Any]] =
      configs.map(_.config).flatten

  object Config:
    def aggregate(configs: Seq[Config]): Config =
      AggregateConfig(configs.toList)

  case class Volume(
      mountPath: String,
      name: String,
      subPath: Option[String] = None
  )
  case class Container(
      name: String,
      image: String,
      ports: List[Port] = List(),
      volumes: List[Volume] = List()
  )
  case class Port(name: String, port: Int, protocol: String = "TCP")

  trait Deployment extends SingleConfig:
    /** Defines how many replicas of the pods defined in this deployment should
      * be created
      *
      * Default to one.
      */
    def replicas: Int = 1

    /** We will always match on the labels of the deployment as a selector for
      * the pods in our replicaset.
      *
      * You can override this if you want more advanced behaviour
      */
    def selector: Map[String, Any] = Map(
      "matchLabels" -> labels
    )

    /** Exposes a list of volumes as defined by the containers in this
      * deployment
      */
    def volumes: List[Volume] =
      containers.map(_.volumes).flatten

    /** Exposes a map of ports (name -> containerPort) pairs from the container
      * definitions in the pod
      */
    def ports: Map[String, Port] =
      containers.map(_.ports.map(port => port.name -> port).toMap).flatten.toMap

    /** The containers in the deployment */
    def containers: List[Container]

    def apiVersion: String = "apps/v1"
    def kind: String = "Deployment"
    def spec: Map[String, Any] = Map(
      "replicas" -> replicas,
      "selector" -> selector,
      "template" -> Map(
        "metadata" -> Map("labels" -> labels),
        "spec" -> Map(
          "containers" -> containers.map { container =>
            Map(
              "name" -> container.name,
              "image" -> container.image,
              "ports" -> container.ports
                .map(port =>
                  Map("name" -> port.name, "containerPort" -> port.port)
                )
                .toArray,
              "volumeMounts" -> container.volumes
                .map(mount =>
                  Map(
                    "mountPath" -> mount.mountPath,
                    "name" -> mount.name
                  ) ++ (if mount.subPath.isDefined then
                          Set(("subPath" -> mount.subPath.get))
                        else Set())
                )
                .toArray
            ),
          }.toArray,
          "volumes" -> volumes
            .map(volume =>
              Map(
                "name" -> volume.name,
                "persistentVolumeClaim" -> Map(
                  "claimName" -> s"${this.name}-${volume.name}"
                )
              )
            )
            .toArray
        )
      )
    )

  /** Any single config can be automatically converted to a list of single
    * configs, this is to make the aggregation of them easier
    */
  implicit def singleConfigToList(
      singleConfig: SingleConfig
  ): List[SingleConfig] =
    List(singleConfig)

  trait ExposedDeployment extends Deployment:
    /** A mapping from a named port to a domain name */
    def mapping: Map[String, String]

  class Service(val name: String, labels: Map[String, String], port: Port)
      extends SingleConfig:
    def apiVersion = "v1"
    def kind: String = "Service"
    def spec: Map[String, Any] = Map(
      "selector" -> labels,
      "ports" -> Array(
        Map(
          "protocol" -> port.protocol,
          "port" -> port.port,
          "targetPort" -> port.port
        )
      )
    )

  class IngressRoute(
      hostname: String,
      service: Port,
      certResolver: Option[String]
  ) extends SingleConfig:
    def apiVersion: String = "traefik.containo.us/v1alpha1"
    def kind: String = "IngressRoute"
    def name: String =
      s"$hostname-route" + (if certResolver.isDefined then "-tls" else "")
    def spec: Map[String, Any] = Map(
      "entryPoints" -> Array(
        if certResolver.isDefined then "https" else "http"
      ),
      "routes" -> Array(
        Map(
          "match" -> s"Host(`$hostname`)",
          "kind" -> "Rule",
          "services" -> Array(
            Map(
              "name" -> service.name,
              "port" -> service.port
            )
          )
        )
      )
    ) ++ (if certResolver.isDefined then
            Map("tls" -> Map("certResolver" -> certResolver.get))
          else Map())

  trait IngressConfig:
    def certResolver: String

  object Ingress:

    /** Create configuration to expose a particular deployment to the outside
      * world, on both http and https
      */
    def expose(deployment: ExposedDeployment)(using ic: IngressConfig): Config =
      new Config {
        def config = Config
          .aggregate(
            deployment.mapping
              .map { case (name, domainname) =>
                List(
                  Service(
                    s"${deployment.name}-${name}-service",
                    deployment.labels,
                    deployment.ports(name)
                  ),
                  IngressRoute(
                    domainname,
                    Port(
                      s"${deployment.name}-${name}-service",
                      deployment.ports(name).port
                    ),
                    None
                  ),
                  IngressRoute(
                    domainname,
                    Port(
                      s"${deployment.name}-${name}-service",
                      deployment.ports(name).port
                    ),
                    Some(ic.certResolver)
                  )
                )
              }
              .flatten
              .toSeq
          )
          .config
      }

  class PersistentVolumeClaim(val name: String) extends SingleConfig:
    def apiVersion: String = "v1"
    def kind = "PersistentVolumeClaim"
    def storageClassName = "local-path"
    def size = "100Gi"
    def spec: Map[String, Any] = Map(
      "accessModes" -> Array("ReadWriteOnce"),
      "resources" -> Map(
        "requests" -> Map("storage" -> size)
      ),
      "storageClassName" -> storageClassName
    )

  object PVC:
    /** Creates a local claim for all the volumes listed in the given deployment
      */
    def localClaim(deployment: Deployment): Config = new Config {
      def config = Config
        .aggregate(
          deployment.volumes.map(volume =>
            PersistentVolumeClaim(s"${deployment.name}-${volume.name}")
          )
        )
        .config
    }

  object Kubectl:
    def applyConf(kubeconfig: Config): Unit =
      implicit val formats = Serialization.formats(NoTypeHints)
      val yamlConfs = kubeconfig.config.map(write)
      yamlConfs.foreach(println)

}
