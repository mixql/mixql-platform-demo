val scala3Version = "3.2.1"

lazy val root = project
  .in(file("."))
  .enablePlugins(JavaAppPackaging)
  .settings(
    name := "mixql-platform-demo",
    version := "0.1.0-SNAPSHOT",

    scalaVersion := scala3Version,

    libraryDependencies ++= {
      val vScallop = "4.1.0"
      Seq(
        "org.rogach" %% "scallop" % vScallop,
        "com.typesafe" % "config" % "1.4.2",
        "org.scalameta" %% "munit" % "0.7.29" % Test,
        "org.mixql" %% "mixql-cluster" % "0.1.0-SNAPSHOT",
        "org.mixql" %% "mixql-engine-demo" % "0.1.0-SNAPSHOT" % Test
      )
    }
  )

lazy val stageAll = taskKey[Unit]("Stage all projects")
lazy val prePackArchive = taskKey[Unit]("action before making release tar.gz")
lazy val packArchive = taskKey[Unit]("Making release tar.gz")
lazy val makeTarGZ = taskKey[Unit]("Pack target dist tar.gz")

val projects_stage = ScopeFilter(inProjects(root), inConfigurations(Universal))

stageAll := {
  stage.all(projects_stage).value
}

packArchive := Def.sequential(stageAll, prePackArchive, makeTarGZ).value

import com.typesafe.config.{Config, ConfigFactory}

val configMixqlPlatform = ConfigFactory.parseFile(new File("./mixql_platform.conf"))

prePackArchive := {
  implicit val log = streams.value.log

  IO.delete(new File(s"target/${name.value}-${version.value}"))
  val targetStageDir = (root / baseDirectory).value / "target" / s"${name.value}-${version.value}"
  copyStageModule(targetStageDir, (root / baseDirectory).value / "target" / "universal" / "stage")

  import scala.util.{Try, Failure, Success}


  Try {
    configMixqlPlatform.getStringList("org.mixql.engines")
  } match {
    case Failure(exception) => log.error("Error while getting list of engine uris: " +
      exception.getMessage
    )
    case Success(uris) =>
      import scala.collection.JavaConverters._
      uris.asScala.foreach(
        uri => {
          val (name, version) = parseUri(uri)
          downloadAndExtractModule(name, version, uri, new File(s"target/$name-$version.tar.gz"))
          copyStageModule(targetStageDir, (root / baseDirectory).value / "target" / s"$name-$version")
        }
      )
  }
}

import sbt.internal.util.ManagedLogger
def copyStageModule(targetStageDir: File, sourceStageDir: File)(implicit log: ManagedLogger) = {
  val targetStageBin = targetStageDir / "bin"
  val targetStageLib = targetStageDir / "lib"

  val sourceStageLib = sourceStageDir / "lib"
  val sourceStageBin = sourceStageDir / "bin"
  log.info(s"Copying libs dir ${sourceStageLib.getAbsolutePath} to ${
    targetStageLib.getAbsolutePath
  }")
  IO.copyDirectory(sourceStageLib, targetStageLib)
  log.info(s"Copying bin dir ${sourceStageBin.getAbsolutePath} to ${
    targetStageBin.getAbsolutePath
  }")
  IO.copyDirectory(sourceStageBin, targetStageBin)
}


def downloadAndExtractModule(name: String, version: String, uri: String, localTarGzFile: File): Unit = {
  if (version.endsWith("-SNAPSHOT")) {
    IO.delete(localTarGzFile)
    IO.delete(new File(s"target/$name-$version"))
  }

  if (!localTarGzFile.exists()) {
    FileDownloader.downloadFile(uri, localTarGzFile)
    IO.delete(new File(s"target/$name-$version"))
    TarGzArchiver.extractTarGz(localTarGzFile, new File(s"target/"))
  } else {
    if (!new File(s"target/$name-$version").exists()) {
      TarGzArchiver.extractTarGz(localTarGzFile, new File(s"target/"))
    }
  }
}

def parseUri(uri: String): (String, String) = {
  if (!uri.endsWith(".tar.gz"))
    throw new Exception("Uri must be for downloading tar gz archive. Example: " +
      "http://127.0.0.1:8080/org/mixql/engine-demo/mixql-engine-demo-0.1.0-SNAPSHOT.tar.gz"
    )
  val endPart = uri.split("""/""").last
  var name = """[A-Za-z\-]+""".r.findFirstIn(endPart).get
  if (name.endsWith("-")) name = name.dropRight(1)
  val version = """\d+\.\d+\.\d+(-SNAPSHOT)?""".r.findFirstIn(endPart).get
  (name, version)
}

makeTarGZ := {
  import sbt.internal.util.ManagedLogger
  implicit val log = streams.value.log

  IO.delete(new File(s"target/${name.value}-${version.value}.tar.gz"))

  val sourceDir = (root / target).value / s"${name.value}-${version.value}"

  log.info(s"Pack ${sourceDir.getAbsolutePath}")

  TarGzArchiver.createTarGz(new File(s"target/${name.value}-${version.value}.tar.gz"),
    s"${name.value}-${version.value}/",
    sourceDir / "bin",
    sourceDir / "lib"
  )
  log.info("Task `packArchive` completed successfully")
}
