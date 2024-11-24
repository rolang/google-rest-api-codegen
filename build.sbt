ThisBuild / description := "Google Cloud client code generator"
ThisBuild / organization := "com.anymindgroup"
ThisBuild / licenses := Seq(License.Apache2)
ThisBuild / homepage := Some(url("https://anymindgroup.com"))
ThisBuild / scalaVersion := "3.3.4"
ThisBuild / scalafmt := true
ThisBuild / scalafmtSbtCheck := true
ThisBuild / version ~= { v => if (v.contains('+')) s"${v.replace('+', '-')}-SNAPSHOT" else v }

lazy val testSettings = Seq(
  Compile / scalacOptions ++= Seq("-Xmax-inlines:64"),
  scalacOptions -= "-Xfatal-warnings"
)

lazy val noPublish = Seq(
  publish := {},
  publishLocal := {},
  publishArtifact := false,
  publish / skip := true
)

lazy val sttpClient4Version = "4.0.0-M19"

lazy val sttpClient3Version = "3.10.1"

lazy val zioVersion = "2.1.13"

lazy val zioJsonVersion = "0.7.3"

lazy val jsoniterVersion = "2.31.3"

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
  .settings(
    name := "gcp-codegen-cli",
    moduleName := "gcp-codegen-cli"
  )

lazy val cliDestBin: File = {
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

  cliTarget / "bin" / name
}

lazy val buildCliBinary = taskKey[File]("")
buildCliBinary := {
  val built = (cli / Compile / nativeLinkReleaseFast).value
  val destZip = new File(s"${cliDestBin.getPath()}.zip")

  IO.copyFile(built, cliDestBin)
  sLog.value.info(s"Built cli binary in $cliDestBin")

  IO.zip(Seq((built, "codegen")), destZip, None)
  sLog.value.info(s"Built cli binary zip in $destZip")

  cliDestBin
}

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
      httpSource <- Seq("Sttp4", "Sttp3")
      jsonCodec <- Seq("ZioJson", "Jsoniter")
      arrayType <- Seq("ZioChunk", "List")
    } yield {
      Project
        .apply(
          id = s"test-${httpSource}-${jsonCodec}-${arrayType}".toLowerCase(),
          base = file(s"modules/test-${httpSource}-${jsonCodec}-${arrayType}".toLowerCase())
        )
        .settings(testSettings)
        .settings(noPublish)
        .settings(
          Compile / sourceGenerators += codegenTask(httpSource, jsonCodec, arrayType),
          libraryDependencies ++= Seq(
            "org.scalameta" %% "munit" % munitVersion % Test
          ) ++ dependencyByConfig(httpSource = httpSource, jsonCodec = jsonCodec, arrayType = arrayType)
        )
    }
}

def codegenTask(httpSource: String, jsonCodec: String, arrayType: String) = Def.task {
  val logger = streams.value.log
  val cliBin = cliDestBin

  if (!cliBin.exists()) {
    logger.error(s"Command line binary ${cliBin.getPath()} was not found. Run 'sbt buildCliBinary' first.")
    List.empty[File]
  } else {
    val outDir = (Compile / sourceManaged).value
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

    for {
      (specPkg, specPath) <- List(
        "gcp.pubsub.v1" -> resourcePath("pubsub_v1.json"),
        "gcp.storage.v1" -> resourcePath("storage_v1.json"),
        "gcp.aiplatform.v1" -> resourcePath("aiplatform_v1.json"),
        "gcp.iamcredentials.v1" -> resourcePath("iamcredentials_v1.json")
      )
      basePkgName = s"$specPkg.${httpSource}_${jsonCodec}_${arrayType}".toLowerCase()
    } yield {
      val cmd =
        List(
          s"./${cliBin.getPath()}",
          s"--specs=$specPath",
          s"--resources-pkg=$basePkgName.resources",
          s"--schemas-pkg=$basePkgName.schemas",
          s"--out-dir=${outSrcDir.getPath()}",
          s"--dialect=$dialect",
          s"--http-source=$httpSource",
          s"--json-codec=$jsonCodec",
          s"--array-type=$arrayType"
        ).mkString(" ")

      cmd ! ProcessLogger(l => logger.info(l))
    }

    val files = listFilesRec(List(outDir), Nil)

    // formatting (may need to find another way...)
    logger.info(s"Formatting sources in $outDir...")
    s"scala-cli fmt --scalafmt-conf=./.scalafmt.conf $outDir" ! ProcessLogger(_ => ()) // add logs when needed
    s"rm -rf $outDir/.scala-build".!!
    logger.success("Formatting done")

    files
  }
}
