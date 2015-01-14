import sbt._
import com.paypal.horizon.{ ChangelogReleaseSteps, BuildUtilities }
import com.paypal.horizon.BuildUtilitiesKeys
import sbtrelease._
import sbtrelease.ReleasePlugin.ReleaseKeys
import sbtunidoc.Plugin._

import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.api.Git
import scala.util.Try
import java.util.Calendar

package object GitInfo {
  private val repo = (new FileRepositoryBuilder).findGitDir.build
  private val git = new Git(repo)
  private val config = repo.getConfig

  val name = (Option(config.getString("user", null, "name")), Option(config.getString("user", null, "email"))) match {
    case (Some(name), Some(email)) => s"$name ($email)"
    case (Some(name), _)           => name
    case (_, Some(email))          => email
    case _                         => "unknown"
  }

  import scala.collection.JavaConversions._
  def logToSha(sha: Option[String], limit: Int): String = {
    val baseStream = Option(git.log.call.iterator) match {
      case Some(iterator) => iterator.toStream
      case _              => Stream()
    }

    val filtered = baseStream.take(limit).takeWhile(c => Some(c.name) != sha)

    val message = filtered.flatMap {
      case commit if commit.getParentCount > 1 => {
        val (firstLine, restLines) = commit.getFullMessage.split("\n").toList match {
          case first :: blank :: rest if blank.trim.isEmpty => (first, rest)
          case first :: rest                                => (first, rest)
          case other                                        => (other.mkString, Nil)
        }

        val f = s"* **$firstLine**\n"
        val r = restLines.map { rest =>
          s"  * $rest"
        }.mkString("\n")

        Some(f + r)
      }
      case _ => None
    }.mkString("\n")

    message
  }

  def currentSha(): String = {
    Option(git.log.call.iterator).flatMap(_.toList.headOption).getOrElse(throw new Exception("uninitialized repo")).name
  }

  def currentBranch(): String = repo.getBranch

  lazy val gitInfo = TaskKey[Seq[File]]("gitInfo")
  lazy val gitInfoFile = SettingKey[File]("gitInfoFile")

  def doGitTask(file: File): Seq[File] = {
    val sha = currentSha()
    val shaLine = s"""git.sha.current="$sha" """
    Try(IO.readLines(file)).toOption.flatMap(_.headOption) match {
      case Some(test) if test == shaLine => {
        println(file.toString + " already exists")
        Seq(file)
      }
      case _ => {
        println(s"Writing out $sha to " + file.toString)
        IO.write(file, shaLine)
        Seq(file)
      }
    }
  }

}

package object Release {

  object Helpers {
    def changeLog(): String = {
      val calendar = Calendar.getInstance()
      val year = calendar.get(Calendar.YEAR)
      val month = calendar.get(Calendar.MONTH) + 1

      "changelogs/CHANGELOG-%04d%02d.md".format(year, month)
    }
  }

  object CustomKeys {
    lazy val sha = SettingKey[Option[String]]("sha")

    lazy val shaFile = SettingKey[File]("shaFile")

    lazy val currentSha = AttributeKey[String]("currentSha")

    lazy val pushProcess = SettingKey[Seq[ReleaseStep]]("push-process")
  }

  object CustomSteps {
    lazy val extractPreviousSha: ReleaseStep = { st: State =>
      val extracted = Project.extract(st)
      val maybeSha: Option[String] = Try(extracted.get(CustomKeys.sha)).toOption.flatten
      maybeSha match {
        case Some(sha) => st.log.info(s"Found sha hash for previous version: $sha")
        case None      => st.log.warn("No sha hash for previous version found!")
      }

      st.put(CustomKeys.sha.key, maybeSha)
    }

    lazy val getCurrentSha: ReleaseStep = { st: State =>
      st.put(CustomKeys.currentSha, GitInfo.currentSha())
    }

    lazy val touchChangeLog: ReleaseStep = { st: State =>
      val f = file(Project.extract(st).get(BuildUtilitiesKeys.changelog))
      IO.touch(f)
      st
    }

    lazy val changeLogMessage: ReleaseStep = { st: State =>

      val sha = st.get(CustomKeys.sha.key).flatten

      //TODO: I really don't understand why paypal/horizon uses System properties to pass this around.
      System.setProperty("changelog.msg", GitInfo.logToSha(sha, limit = 300))
      System.setProperty("changelog.author", GitInfo.name)

      st
    }

    lazy val setSha: ReleaseStep = { st: State =>
      val shaFile = Project.extract(st).get(CustomKeys.shaFile)
      st.get(CustomKeys.currentSha) foreach { currentSha =>
        val shaLine = s"""Release.CustomKeys.sha := Some("$currentSha")"""
        IO.write(shaFile, shaLine)
      }
      st
    }

    lazy val commitSha: ReleaseStep = { st: State =>
      val extracted = Project.extract(st)
      val file = extracted.get(CustomKeys.shaFile)
      val git = extracted.get(ReleaseKeys.versionControlSystem).getOrElse(sys.error("Aborting release. Working directory is not a repository of a recognized VCS."))
      val base = git.baseDir

      val relativePath = IO.relativize(base, file).getOrElse(sys.error("Version file [%s] is outside of this VCS repository with base directory [%s]!" format (file, base)))
      git.add(relativePath) !! st.log

      val status = (git.status.!!).trim

      val version = st.get(ReleaseKeys.versions).map(_._2).getOrElse("")

      val newState = if (status.nonEmpty) {
        git.commit(s"Update sha.sbt for version $version") ! st.log
      }
      st
    }

    /**
     * Push steps
     */
    lazy val setRemoteCurrent: ReleaseStep = { st: State =>
      val extracted = Project.extract(st)
      val git = extracted.get(ReleaseKeys.versionControlSystem).getOrElse(sys.error("Aborting release. Working directory is not a repository of a recognized VCS."))

      val currentBranch = git.currentBranch
      st.log.info(s"Setting remotes to current branch for $currentBranch")
      git.cmd("config", s"branch.$currentBranch.merge", s"$currentBranch") !! st.log
      git.cmd("config", s"branch.$currentBranch.remote", "origin") !! st.log
      st.log.warn(s"PUSHING TO $currentBranch REMOTE")
      st
    }

    lazy val setRemoteMaster: ReleaseStep = { st: State =>
      val extracted = Project.extract(st)
      val git = extracted.get(ReleaseKeys.versionControlSystem).getOrElse(sys.error("Aborting release. Working directory is not a repository of a recognized VCS."))

      val currentBranch = git.currentBranch
      st.log.info(s"Setting remotes to master for $currentBranch")

      git.cmd("config", s"branch.$currentBranch.merge", "master") !! st.log
      git.cmd("config", s"branch.$currentBranch.remote", "origin") !! st.log
      st.log.warn("PUSHING TO MASTER REMOTE")
      st
    }

    lazy val unsetRemote: ReleaseStep = { st: State =>
      val extracted = Project.extract(st)
      val git = extracted.get(ReleaseKeys.versionControlSystem).getOrElse(sys.error("Aborting release. Working directory is not a repository of a recognized VCS."))

      val currentBranch = git.currentBranch
      st.log.info(s"Unsetting remotes")

      git.cmd("config", "--unset", s"branch.$currentBranch.merge") !! st.log
      git.cmd("config", "--unset", s"branch.$currentBranch.remote") !! st.log
      st
    }

    lazy val pullChanges: ReleaseStep = { st: State =>
      val extracted = Project.extract(st)
      val git = extracted.get(ReleaseKeys.versionControlSystem).getOrElse(sys.error("Aborting release. Working directory is not a repository of a recognized VCS."))
      git.currentBranch match {
        case currentBranch if currentBranch == "develop" => {
          st.log.info("Pulling existing develop branch from origin. Note: git logs will be marked as error")
          git.cmd("pull", "-r", "origin", "develop") !! st.log
          st
        }
        case _ => sys.error("Error. Current branch must be set to develop")
      }
    }
  }

  private val customReleaseProcess = Seq[ReleaseStep](
    CustomSteps.pullChanges,
    ReleaseStateTransformations.checkSnapshotDependencies,
    ReleaseStateTransformations.inquireVersions,
    CustomSteps.getCurrentSha,
    CustomSteps.extractPreviousSha,
    ReleaseStateTransformations.runTest,
    CustomSteps.changeLogMessage,
    CustomSteps.touchChangeLog,
    ChangelogReleaseSteps.checkForChangelog,
    ReleaseStateTransformations.setReleaseVersion,
    ReleaseStateTransformations.commitReleaseVersion,
    ChangelogReleaseSteps.updateChangelog,
    CustomSteps.setSha,
    CustomSteps.commitSha,
    ReleaseStateTransformations.tagRelease,
    ReleaseStateTransformations.setNextVersion,
    ReleaseStateTransformations.commitNextVersion
  )

  private val pushProcess = Seq[ReleaseStep](
    CustomSteps.setRemoteCurrent,
    ReleaseStateTransformations.pushChanges,
    CustomSteps.setRemoteMaster,
    ReleaseStateTransformations.pushChanges,
    CustomSteps.unsetRemote
  )

  private val pushCommand = Command.command("push") { st: State =>
    val FailureCommand = "--failure--"

    val extracted = Project.extract(st)
    val pushParts = extracted.get(CustomKeys.pushProcess)
    val startState = st
      .copy(onFailure = Some(FailureCommand))

    def filterFailure(f: State => State)(s: State): State = {
      s.remainingCommands match {
        case FailureCommand :: tail => s.fail
        case _                      => f(s)
      }
    }

    val removeFailureCommand = { s: State =>
      s.remainingCommands match {
        case FailureCommand :: tail => s.copy(remainingCommands = tail)
        case _                      => s
      }
    }

    val process = pushParts.map { step =>
      filterFailure(step.action) _
    }

    Function.chain(process :+ removeFailureCommand)(startState)
  }

  //exposed
  val settings = ReleasePlugin.releaseSettings ++ Seq(
    ReleaseKeys.releaseProcess := customReleaseProcess,
    BuildUtilitiesKeys.changelog := Helpers.changeLog(),
    CustomKeys.shaFile := file("sha.sbt"),
    CustomKeys.pushProcess := pushProcess,
    Keys.commands += pushCommand
  )
}
