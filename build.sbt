ThisBuild / description := "Google Cloud client code generator"
ThisBuild / organization := "dev.rolang"
ThisBuild / licenses := Seq(License.MIT)
ThisBuild / homepage := Some(url("https://github.com/rolang/google-rest-api-codegen"))
ThisBuild / scalaVersion := "3.7.1"
ThisBuild / version ~= { v => if (v.contains('+')) s"${v.replace('+', '-')}-SNAPSHOT" else v }
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/rolang/google-rest-api-codegen"),
    "scm:git@github.com:rolang/google-rest-api-codegen.git"
  )
)
ThisBuild / developers := List(
  Developer(
    id = "rolang",
    name = "Roman Langolf",
    email = "rolang@pm.me",
    url = url("https://rolang.dev")
  )
)
ThisBuild / sonatypeCredentialHost := xerial.sbt.Sonatype.sonatypeCentralHost

lazy val testSettings = Seq(
  Compile / scalacOptions ++= Seq("-Xmax-inlines:64"),
  scalacOptions -= "-Xfatal-warnings"
)

lazy val publishSettings = List(
  publishTo := sonatypePublishToBundle.value
)

lazy val noPublish = Seq(
  publish := {},
  publishLocal := {},
  publishArtifact := false,
  publish / skip := true
)

lazy val sttpClient4Version = "4.0.9"

lazy val zioVersion = "2.1.16"

lazy val zioJsonVersion = "0.7.39"

lazy val jsoniterVersion = "2.33.2"

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

// for supporting code inspection / testing of generated code via test_gen.sh script
lazy val testLocal = (project in file("test-local"))
  .settings(
    libraryDependencies ++= dependencyByConfig("Sttp4", "Jsoniter", "ZioChunk")
  )
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
      "com.lihaoyi" %%% "upickle" % "4.1.0"
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
    moduleName := "gcp-codegen-cli",
    nativeConfig := nativeConfig.value.withMultithreading(false)
  )

def dependencyByConfig(httpSource: String, jsonCodec: String, arrayType: String): Seq[ModuleID] = {
  (httpSource match {
    case "Sttp4" => Seq("com.softwaremill.sttp.client4" %% "core" % sttpClient4Version)
    case other   => throw new InterruptedException(s"Invalid http source: $other")
  }) ++ (jsonCodec match {
    case "Jsoniter" =>
      Seq(
        "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % jsoniterVersion,
        "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % jsoniterVersion % "compile-internal"
      )
    case "ZioJson" => Seq("dev.zio" %% "zio-json" % zioJsonVersion)
    case other     => throw new InterruptedException(s"Invalid json codec: $other")
  }) ++ (arrayType match {
    case "ZioChunk"                  => Seq("dev.zio" %% "zio" % zioVersion)
    case "List" | "Vector" | "Array" => Nil
    case other                       => throw new InterruptedException(s"Invalid array type: $other")
  })
}

lazy val testProjects: CompositeProject = new CompositeProject {
  override def componentProjects: Seq[Project] =
    for {
      (apiName, apiVersion) <- Seq(
        "pubsub" -> "v1",
        "storage" -> "v1",
        "aiplatform" -> "v1",
        "iamcredentials" -> "v1",
        "redis" -> "v1"
      )
      httpSource <- Seq("Sttp4")
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

lazy val cliBinFile: File = {
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

  cliTarget / name
}

lazy val buildCliBinary = taskKey[File]("")
buildCliBinary := {
  val built = (cli / Compile / nativeLinkReleaseFast).value
  IO.copyFile(built, cliBinFile)
  cliBinFile
}

def codegenTask(
    specs: (String, String),
    httpSource: String,
    jsonCodec: String,
    arrayType: String
) = Def.taskDyn {
  val logger = streams.value.log
  val cliBin = cliBinFile

  Def.task {
    val (apiName, apiVersion) = specs

    val outDir = (Compile / sourceManaged).value
    val outPathRel = outDir.relativeTo(new File(".")).map(_.getPath()).getOrElse("")
    val outSrcDir = outDir / "scala"

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

    val errs = scala.collection.mutable.ListBuffer.empty[String]
    (List(
      s"./${cliBin.getPath()}",
      s"-specs=${resourcePath(s"${apiName}_$apiVersion.json")}",
      s"-out-dir=${outSrcDir.getPath()}",
      s"-out-pkg=$basePkgName",
      s"-http-source=$httpSource",
      s"-json-codec=$jsonCodec",
      s"-array-type=$arrayType"
    ).mkString(" ") ! ProcessLogger(l => logger.info(l), e => errs += e)) match {
      case 0 => ()
      case c => throw new InterruptedException(s"Failure on code generation: ${errs.mkString("\n")}")
    }

    val files = listFilesRec(List(outDir), Nil)
    IO.delete(outDir / ".scala-build")
    files
  }
}
