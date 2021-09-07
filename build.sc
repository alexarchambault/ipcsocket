import $ivy.`de.tototec::de.tobiasroeser.mill.vcs.version_mill0.9:0.1.1`
import $file.settings, settings.{JniModule, JniPublishModule}

import de.tobiasroeser.mill.vcs.version.VcsVersion

import mill._
import mill.scalalib._
import mill.scalalib.publish._

import scala.concurrent.duration.Duration

object ipcsocket extends MavenModule with JniModule with JniPublishModule {
  def ivyDeps = super.ivyDeps() ++ Seq(
    ivy"net.java.dev.jna:jna:5.8.0",
    ivy"net.java.dev.jna:jna-platform:5.8.0"
  )

  def windowsDllName = "ipcsocket"
  def unixLibName = "ipcsocket"

  def windowsCSources = T.sources {
    val sources = super.windowsCSources()
    sources.map(r => PathRef(r.path / "win32"))
  }

  def unixCSources = T.sources {
    val sources = super.unixCSources()
    sources.map(r => PathRef(r.path / "unix"))
  }

  def windowsLinkingLibs = T{
    super.windowsLinkingLibs() ++ Seq("kernel32", "advapi32")
  }
  def windowsCOptions = T{
    super.windowsCOptions() ++ Seq(
      "-D__WIN__"
    )
  }
  def windowsDllCOptions = T{
    super.windowsDllCOptions() ++ Seq(
      "-ffreestanding"
    )
  }

  def jniArtifactDir =
    if (System.getenv("CI") == null) None
    else Some(os.Path("artifacts/", os.pwd))

  def publishVersion = T {
    val state = VcsVersion.vcsState()
    if (state.commitsSinceLastTag > 0) {
      val versionOrEmpty = state.lastTag
        .map(_.stripPrefix("v"))
        .map { tag =>
          val idx = tag.lastIndexOf(".")
          if (idx >= 0) tag.take(idx + 1) + (tag.drop(idx + 1).toInt + 1).toString + "-SNAPSHOT"
          else ""
        }
        .getOrElse("0.0.1-SNAPSHOT")
      Some(versionOrEmpty)
        .filter(_.nonEmpty)
        .getOrElse(state.format())
    } else
      state
        .lastTag
        .getOrElse(state.format())
        .stripPrefix("v")
  }
  def pomSettings = PomSettings(
    description = artifactName(),
    organization = "com.github.alexarchambault.tmp.ipcsocket",
    url = "https://github.com/alexarchambault/ipcsocket",
    licenses = Seq(License.`Apache-2.0`),
    versionControl = VersionControl.github("alexarchambault", "ipcsocket"),
    developers = Seq(
      Developer("alexarchambault", "Alex Archambault", "https://github.com/alexarchambault")
    )
  )

  object test extends Tests {
    def ivyDeps = super.ivyDeps() ++ Seq(
      ivy"com.novocode:junit-interface:0.11"
    )
  }
}

def publishSonatype(tasks: mill.main.Tasks[PublishModule.PublishData]) =
  T.command {
    import scala.concurrent.duration.DurationInt
    val timeout = 10.minutes
    val credentials = sys.env("SONATYPE_USERNAME") + ":" + sys.env("SONATYPE_PASSWORD")
    val pgpPassword = sys.env("PGP_PASSWORD")
    val data = define.Task.sequence(tasks.value)()

    doPublishSonatype(
      credentials = credentials,
      pgpPassword = pgpPassword,
      data = data,
      timeout = timeout,
      log = T.ctx().log
    )
  }

def doPublishSonatype(
  credentials: String,
  pgpPassword: String,
  data: Seq[PublishModule.PublishData],
  timeout: Duration,
  log: mill.api.Logger
): Unit = {

  val artifacts = data.map {
    case PublishModule.PublishData(a, s) =>
      (s.map { case (p, f) => (p.path, f) }, a)
  }

  val isRelease = {
    val versions = artifacts.map(_._2.version).toSet
    val set = versions.map(!_.endsWith("-SNAPSHOT"))
    assert(set.size == 1, s"Found both snapshot and non-snapshot versions: ${versions.toVector.sorted.mkString(", ")}")
    set.head
  }
  val publisher = new publish.SonatypePublisher(
    uri = "https://oss.sonatype.org/service/local",
    snapshotUri = "https://oss.sonatype.org/content/repositories/snapshots",
    credentials = credentials,
    signed = true,
    gpgArgs = Seq("--detach-sign", "--batch=true", "--yes", "--pinentry-mode", "loopback", "--passphrase", pgpPassword, "--armor", "--use-agent"),
    readTimeout = timeout.toMillis.toInt,
    connectTimeout = timeout.toMillis.toInt,
    log = log,
    awaitTimeout = timeout.toMillis.toInt,
    stagingRelease = isRelease
  )

  publisher.publishAll(isRelease, artifacts: _*)
}
