resolvers += "Nexus Snapshots" at "http://192.168.0.105:8081/nexus/content/groups/public/"

resolvers += "maven2" at "http://repo1.maven.org/maven2"

addSbtPlugin("com.typesafe.sbt" % "sbt-scalariform" % "1.3.0")

// addSbtPlugin("com.github.mpeltonen" % "sbt-idea" % "1.6.0")

// addSbtPlugin("com.typesafe.sbteclipse" % "sbteclipse-plugin" % "2.2.0")
