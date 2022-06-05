val scala3Version = "3.1.2"

lazy val root = project
  .in(file("."))
  .settings(
    name := "ksslib",
    version := "0.1",

    scalaVersion := scala3Version,
    libraryDependencies += "org.json4s" %% "json4s-native" % "4.0.5",
    libraryDependencies += "org.json4s" %% "json4s-core" % "4.0.5"
  )
