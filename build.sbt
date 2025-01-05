ThisBuild / description := "Google Cloud client code generator"
ThisBuild / organization := "dev.rolang"
ThisBuild / licenses := Seq(License.MIT)
ThisBuild / homepage := Some(url("https://github.com/rolang/google-rest-api-codegen"))
ThisBuild / scalaVersion := "3.3.4"
ThisBuild / scalafmt := true
ThisBuild / scalafmtSbtCheck := true
ThisBuild / version ~= { v => if (v.contains('+')) s"${v.replace('+', '-')}-SNAPSHOT" else v }

lazy val testSettings = Seq(
  Compile / scalacOptions ++= Seq("-Xmax-inlines:64"),
  scalacOptions -= "-Xfatal-warnings"
)

lazy val publishSettings = List(
  credentials += Credentials(
    "Sonatype Nexus Repository Manager",
    "oss.sonatype.org",
    sys.env.getOrElse("SONATYPE_USERNAME", ""),
    sys.env.getOrElse("SONATYPE_PASSWORD", "")
  ),
  usePgpKeyHex("537C76F3EFF1B9BE6FD238B442BD95234C9636F3"),
  sonatypeCredentialHost := "oss.sonatype.org",
  sonatypeRepository := s"https://${sonatypeCredentialHost.value}/service/local",
  publishTo := sonatypePublishToBundle.value
)

lazy val noPublish = Seq(
  publish := {},
  publishLocal := {},
  publishArtifact := false,
  publish / skip := true
)

lazy val sttpClient4Version = "4.0.0-M22"

lazy val sttpClient3Version = "3.10.2"

lazy val zioVersion = "2.1.14"

lazy val zioJsonVersion = "0.7.3"

lazy val jsoniterVersion = "2.33.0"

lazy val munitVersion = "1.0.2"

addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")

lazy val root = (project in file("."))
  .aggregate(
    core.native,
    core.jvm,
    cli
  )
  .aggregate(testProjects.componentProjects.map(p => LocalProject(p.id)) *)
  .settings(noPublish)

lazy val core = crossProject(JVMPlatform, NativePlatform)
  .in(file("modules/core"))
  .settings(publishSettings)
  .settings(
    name := "gcp-codegen",
    moduleName := "gcp-codegen"
  )
  .settings(
    libraryDependencies ++= Seq(
      "com.lihaoyi" %%% "upickle" % "4.0.2"
    )
  )

lazy val cli = project
  .in(file("modules/cli"))
  .aggregate(core.native)
  .dependsOn(core.native)
  .enablePlugins(ScalaNativePlugin)
  .settings(publishSettings)
  .settings(
    name := "gcp-codegen-cli",
    moduleName := "gcp-codegen-cli"
  )

def dependencyByConfig(httpSource: String, jsonCodec: String, arrayType: String): List[ModuleID] = {
  (httpSource match {
    case "Sttp3" => List("com.softwaremill.sttp.client3" %% "core" % sttpClient3Version)
    case "Sttp4" => List("com.softwaremill.sttp.client4" %% "core" % sttpClient4Version)
    case _       => Nil
  }) ::: (jsonCodec match {
    case "Jsoniter" =>
      List(
        "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % jsoniterVersion,
        "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % jsoniterVersion % "compile-internal"
      )
    case "ZioJson" => List("dev.zio" %% "zio-json" % zioJsonVersion)
  }) ::: (arrayType match {
    case "ZioChunk" => List("dev.zio" %% "zio" % zioVersion)
    case _          => Nil
  })
}

lazy val testProjects: CompositeProject = new CompositeProject {
  override def componentProjects: Seq[Project] =
    for {
      (apiName, apiVersion) <- Seq(
        "pubsub" -> "v1",
        "storage" -> "v1",
        "aiplatform" -> "v1",
        "iamcredentials" -> "v1"
      )
      httpSource <- Seq("Sttp4", "Sttp3")
      jsonCodec <- Seq("ZioJson", "Jsoniter")
      arrayType <- Seq("ZioChunk", "List")
      id = s"test-$apiName-$apiVersion-${httpSource}-${jsonCodec}-${arrayType}".toLowerCase()
    } yield {
      Project
        .apply(
          id = id,
          base = file("modules") / id
        )
        .settings(testSettings)
        .settings(noPublish)
        .settings(
          Compile / sourceGenerators += codegenTask(
            apiName -> apiVersion,
            httpSource,
            jsonCodec,
            arrayType
          ),
          libraryDependencies ++= Seq(
            "org.scalameta" %% "munit" % munitVersion % Test
          ) ++ dependencyByConfig(httpSource = httpSource, jsonCodec = jsonCodec, arrayType = arrayType)
        )
    }
}

def codegenTask(
    specs: (String, String),
    httpSource: String,
    jsonCodec: String,
    arrayType: String
) = Def.taskDyn {
  val logger = streams.value.log
  val cliBin = {
    val cliTarget = new File("modules/cli/target/bin")

    def normalise(s: String) = s.toLowerCase.replaceAll("[^a-z0-9]+", "")
    val props = sys.props.toMap
    val os = normalise(props.getOrElse("os.name", "")) match {
      case p if p.startsWith("linux")                         => "linux"
      case p if p.startsWith("windows")                       => "windows"
      case p if p.startsWith("osx") || p.startsWith("macosx") => "macosx"
      case _                                                  => "unknown"
    }

    val arch = (
      normalise(props.getOrElse("os.arch", "")),
      props.getOrElse("sun.arch.data.model", "64")
    ) match {
      case ("amd64" | "x64" | "x8664" | "x86", bits) => s"x86_${bits}"
      case ("aarch64" | "arm64", bits)               => s"aarch$bits"
      case _                                         => "unknown"
    }

    val name = s"gcp-codegen-$arch-$os"

    val cliDestBin = cliTarget / name
    val built = (cli / Compile / nativeLinkReleaseFast).value
    val destZip = new File(s"${cliDestBin.getPath()}.zip")

    IO.copyFile(built, cliDestBin)

    cliDestBin
  }

  Def.task {
    val (apiName, apiVersion) = specs

    val outDir = (Compile / sourceManaged).value
    val outPathRel = outDir.relativeTo(new File(".")).map(_.getPath()).getOrElse("")
    val scalaV = scalaVersion.value
    val outSrcDir = outDir / (if (scalaV.startsWith("3")) "scala-3" else "scala-2")
    val dialect = if (scalaV.startsWith("3")) "Scala3" else "Scala2"

    @scala.annotation.tailrec
    def listFilesRec(dir: List[File], res: List[File]): List[File] =
      dir match {
        case x :: xs =>
          val (dirs, files) = IO.listFiles(x).toList.partition(_.isDirectory())
          listFilesRec(dirs ::: xs, files ::: res)
        case Nil => res
      }

    import sys.process.*
    logger.info(s"Generating test sources")

    def resourcePath(resource: String) =
      s"modules/test-resources/src/main/resources/$resource"

    IO.delete(outSrcDir)
    IO.createDirectory(outSrcDir)

    val basePkgName = s"gcp.$apiName.$apiVersion.${httpSource}_${jsonCodec}_${arrayType}".toLowerCase()
    val cmd =
      List(
        s"./${cliBin.getPath()}",
        s"--specs=${resourcePath(s"${apiName}_$apiVersion.json")}",
        s"--resources-pkg=$basePkgName.resources",
        s"--schemas-pkg=$basePkgName.schemas",
        s"--out-dir=${outSrcDir.getPath()}",
        s"--dialect=$dialect",
        s"--http-source=$httpSource",
        s"--json-codec=$jsonCodec",
        s"--array-type=$arrayType"
      ).mkString(" ")

    cmd ! ProcessLogger(l => logger.info(l))

    val files = listFilesRec(List(outDir), Nil)

    // formatting (may need to find another way...)
    logger.info(s"Formatting sources in $outPathRel...")
    s"scala-cli fmt --scalafmt-conf=./.scalafmt.conf $outDir" ! ProcessLogger(_ => ()) // add logs when needed
    IO.delete(outDir / ".scala-build")
    logger.success(s"Formatting sources in $outPathRel done")

    files
  }
}
