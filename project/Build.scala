import sbt._
import Keys._

object ProjectBuild extends Build {

  val ScalaVsn = "2.11.1"

  val pluginSettings = Release.settings

  val baseSettings = Seq(
    scalaVersion := ScalaVsn,
    scalacOptions ++= Seq("-deprecation", "-unchecked", "-feature", "-encoding", "utf8"),
    resolvers += "Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/"
  ) ++ pluginSettings

  val appSettings = baseSettings

  //Main API
  lazy val project = Project("project", file(".")).
    enablePlugins(play.PlayScala).
    settings(appSettings: _*).
    settings(
      version := "0.1-SNAPSHOT",
      parallelExecution in Test := false,

      //Compile git info into a config file we can use
      GitInfo.gitInfoFile <<= baseDirectory( _ / "conf/git.conf"),
      GitInfo.gitInfo := GitInfo.gitInfoFile.map(GitInfo.doGitTask).value,
      resourceGenerators in Compile <+= GitInfo.gitInfo //TODO: this is not copied over for 
    )

}
