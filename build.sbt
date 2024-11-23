ThisBuild / name := "Google Cloud client code generator"
ThisBuild / organization := "com.anymindgroup"
ThisBuild / licenses := Seq(License.Apache2)
ThisBuild / homepage := Some(url("https://anymindgroup.com"))
ThisBuild / scalaVersion := "3.5.2"
ThisBuild / scalafmt := true
ThisBuild / scalafmtSbtCheck := true

lazy val commonSettings = Seq(
  Compile / scalacOptions ++= Seq("-Xmax-inlines:64")
)

lazy val sttpClient4Version = "4.0.0-M19"

lazy val sttpClient3Version = "3.10.1"

lazy val zioJsonVersion = "0.7.3"

lazy val jsoniterVersion = "2.31.3"

lazy val munitVersion = "1.0.2"

addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")

lazy val root =
  (project in file("."))
    .aggregate(
      core.jvm,
      core.native,
      cli.native,
      testGen.native,
      testSttp4JsoniterZioChunk.jvm,
      testSttp3JsoniterZioChunk.jvm,
      testSttp4JsoniterList.jvm,
      testsSttp4ZioJsonList.jvm
    )

lazy val core = crossProject(JVMPlatform, NativePlatform)
  .in(file("modules/core"))
  .settings(moduleName := "core")
  .settings(
    libraryDependencies ++= Seq(
      "com.lihaoyi" %%% "upickle" % "4.0.2"
    )
  )

lazy val cli = crossProject(NativePlatform)
  .crossType(CrossType.Full)
  .in(file("modules/cli"))
  .dependsOn(core)
  .settings(
    name := "gcp-codegen-cli",
    moduleName := "gcp-codegen-cli"
  )

lazy val cliNative = cli.native
lazy val buildCliBinary = taskKey[File]("")
buildCliBinary := {
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
  val built = (cliNative / Compile / nativeLink).value
  val destBin = (cliNative / target).value / "bin" / name
  val destZip = (cliNative / target).value / "bin" / s"$name.zip"

  IO.copyFile(built, destBin)
  sLog.value.info(s"Built cli binary in $destBin")

  IO.zip(Seq((built, "codegen")), destZip, None)
  sLog.value.info(s"Built cli binary zip in $destZip")

  destBin
}

lazy val testGen = crossProject(NativePlatform)
  .in(file("modules/test-gen"))
  .dependsOn(core)
  .settings(
    moduleName := "test-gen",
    name := "test-gen"
  )

lazy val testSttp4JsoniterZioChunk = crossProject(JVMPlatform, NativePlatform)
  .in(file("modules/test-sttp4-jsoniter-ziochunk"))
  .settings(commonSettings)
  .settings(
    Compile / sourceGenerators += codegenTask("Sttp4", "Jsoniter", "ZioChunk"),
    libraryDependencies ++= Seq(
      "org.scalameta" %%% "munit" % munitVersion % Test,
      "com.softwaremill.sttp.client4" %%% "core" % sttpClient4Version,
      "dev.zio" %%% "zio" % "2.1.13",
      "com.github.plokhotnyuk.jsoniter-scala" %%% "jsoniter-scala-core" % jsoniterVersion,
      "com.github.plokhotnyuk.jsoniter-scala" %%% "jsoniter-scala-macros" % jsoniterVersion % "compile-internal"
    )
  )

lazy val testSttp3JsoniterZioChunk = crossProject(JVMPlatform, NativePlatform)
  .in(file("modules/test-sttp3-jsoniter-ziochunk"))
  .settings(commonSettings)
  .settings(
    Compile / sourceGenerators += codegenTask("Sttp3", "Jsoniter", "ZioChunk"),
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.client3" %%% "core" % sttpClient3Version,
      "dev.zio" %%% "zio" % "2.1.13",
      "com.github.plokhotnyuk.jsoniter-scala" %%% "jsoniter-scala-core" % jsoniterVersion,
      "com.github.plokhotnyuk.jsoniter-scala" %%% "jsoniter-scala-macros" % jsoniterVersion % "compile-internal"
    )
  )

lazy val testSttp4JsoniterList = crossProject(JVMPlatform, NativePlatform)
  .in(file("modules/test-sttp4-jsoniter-list"))
  .settings(commonSettings)
  .settings(
    Compile / sourceGenerators += codegenTask("Sttp4", "Jsoniter", "List"),
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.client4" %%% "core" % sttpClient4Version,
      "com.github.plokhotnyuk.jsoniter-scala" %%% "jsoniter-scala-core" % jsoniterVersion,
      "com.github.plokhotnyuk.jsoniter-scala" %%% "jsoniter-scala-macros" % jsoniterVersion % "compile-internal"
    )
  )

lazy val testsSttp4ZioJsonList = crossProject(JVMPlatform, NativePlatform)
  .in(file("modules/test-sttp4-ziojson-list"))
  .settings(commonSettings)
  .settings(
    Compile / sourceGenerators += codegenTask("Sttp4", "ZioJson", "List"),
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.client4" %%% "core" % sttpClient4Version,
      "dev.zio" %%% "zio-json" % zioJsonVersion
    )
  )

def codegenTask(httpSource: String, jsonCodec: String, arrayType: String) = Def.task {
  val logger = streams.value.log
  val outDir = (Compile / sourceManaged).value
  val scalaV = scalaVersion.value
  val testGenBin = (testGen.native / Compile / nativeLink).value

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

  s"$testGenBin $outDir $scalaV $httpSource $jsonCodec $arrayType" ! ProcessLogger(l =>
    logger.info(l)
  ) // add logs when needed

  val files = listFilesRec(List(outDir), Nil)

  // formatting (may need to find another way...)
  logger.info(s"Formatting sources in $outDir...")
  s"scala-cli fmt --scalafmt-conf=./.scalafmt.conf $outDir" ! ProcessLogger(_ => ()) // add logs when needed
  s"rm -rf $outDir/.scala-build".!!
  logger.success("Formatting done")

  files
}
