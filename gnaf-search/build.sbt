name := "gnaf-search"

// resourceGenerators in Compile += Def.task {
//   val file = (resourceManaged in Compile).value / "demo" / "myapp.properties"
//   val contents = "name=%s\nversion=%s".format(name.value,version.value)
//   IO.write(file, contents)
//   Seq(file)
// }.taskValue

// mappings in (Compile, packageBin) += {
//   (resourceManaged in Compile).value / "demo" / "myapp.properties" -> "demo/myapp.properties"
// }

mappings in (Compile, packageBin) += {
  new File("gnaf-db/target/generated/version.json") -> "version.json"
}

libraryDependencies ++= Seq(
  "com.github.scopt" %% "scopt" % "3.3.0",
  "com.jsuereth" %% "scala-arm" % "2.0.0-M1",
  "ch.megard" %% "akka-http-cors" % "0.1.2",
  "com.github.swagger-akka-http" %% "swagger-akka-http" % "0.7.0", // adding swagger brings in all the horrible old javax.ws & Jackson dependencies!
  "io.swagger" % "swagger-annotations" % "1.5.9",
  "org.scalatest" %% "scalatest" % "3.0.0" % "test"
  )

libraryDependencies ++= Seq(
  "akka-actor",
  "akka-stream",
  "akka-http-experimental",
  "akka-http-spray-json-experimental",
  "akka-http-testkit"
  ) map ("com.typesafe.akka" %% _ % "2.4.3")

 