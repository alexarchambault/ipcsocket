import java.util.Arrays
import $file.visualstudioutil, visualstudioutil.vcvars
import $file.util, util.toCrLfOpt

import mill._, scalalib._
import mill.scalalib.publish.PublishInfo

import scala.util.Properties

trait GenerateHeaders extends JavaModule {
  def cDirectory = T{
    millSourcePath / "src" / "main" / "c"
  }
  def javacOptions = T{
    val dest = cDirectory()
    Seq("-h", dest.toNIO.toAbsolutePath.toString)
  }
  def compile = T{
    val res = super.compile()
    val dir = cDirectory()
    val headerFiles = Seq(dir).flatMap { dir =>
      if (os.isDir(dir))
        os.walk(dir)
          .filter(p => os.isFile(p) && p.last.endsWith(".h"))
      else
        Nil
    }
    for (f <- headerFiles) {
      val content = os.read.bytes(f)
      for (updatedContent <- toCrLfOpt(content))
        os.write.over(f, updatedContent)
    }
    res
  }
}

trait JniModule
  extends JniWindowsModule with JniWindowsPublish with JniWindowsAddResources
  with JniUnixModule with JniUnixPublish with JniUnixAddResources

private def q = "\""

trait JniUploadDownloadArtifacts extends JniWindowsModule with JniUnixPublish {
  def jniFilesToCopy =
    if (Properties.isWin)
      T {
        Seq(
          "dll" -> windowsDll(),
          "lib" -> windowsLib()
        )
      }
    else
      T {
        Seq(
          "a" -> unixA(),
          unixExtension() -> unixSo()
        )
      }
  def jniLibraryName =
    if (Properties.isWin)
      T {
        windowsDllName()
      }
    else
      T {
        unixLibName()
      }
  def jniClassifier = T {
    if (Properties.isWin) "x86_64-pc-win32"
    else if (Properties.isLinux) "x86_64-pc-linux"
    else if (Properties.isMac) "x86_64-apple-darwin"
    else sys.error("Unrecognized OS")
  }

  def jniCopyFilesTo(dest: String = "artifacts/") = T.command {
    val toCopy0 = jniFilesToCopy()
    val destDir = os.Path(dest, os.pwd)
    val libraryName0 = jniLibraryName()
    val classifier = jniClassifier()

    for ((ext, f) <- toCopy0) {
      val dest = destDir / s"$libraryName0-$classifier.$ext"
      os.copy.over(f.path, dest, createFolders = true)
    }
  }

  def jniAddThisBuildArtifacts: Boolean = true

  def jniArtifactDir = T {
    Option.empty[os.Path]
  }
}

trait JniResourcesModule extends JniUploadDownloadArtifacts with JniWindowsAddResources with JniUnixAddResources {
  // FIXME Some duplication with JniPublishModule.jniArtifactDirExtraPublish
  def jniArtifactDirExtraResources = T {
    val destDir = T.dest / "resources"
    val libraryName0 = jniLibraryName()
    val dirOpt = jniArtifactDir()
    dirOpt.toSeq.filter(os.isDir(_)).flatMap { dir =>
      os.list(dir)
        .filter(_.last.startsWith(libraryName0 + "-"))
        .filter(os.isFile(_))
        .map { f =>
          val name = f.last.stripPrefix(libraryName0 + "-")
          val idx = name.lastIndexOf('.')
          assert(idx >= 0, s"no extension found in $f name")
          val classifier = name.take(idx)
          val osName = classifier match {
            case "x86_64-apple-darwin" => "darwin"
            case "x86_64-pc-linux" => "linux64"
            case "x86_64-pc-win32" => "windows64"
            case other => sys.error(s"Unrecognized classifier: $classifier")
          }
          val ext = name.drop(idx + 1)
          val dest = destDir / "META-INF" / "native" / osName / s"$libraryName0.$ext"
          os.copy.over(f, dest, createFolders = true)
        }
    }
    PathRef(destDir)
  }
  def resources = T.sources {
    super.resources() ++ Seq(jniArtifactDirExtraResources())
  }
}

trait JniPublishModule extends PublishModule with JniUploadDownloadArtifacts with JniWindowsPublish with JniUnixPublish {
  def jniArtifactDirExtraPublish = T {
    val libraryName0 = jniLibraryName()
    val dirOpt = jniArtifactDir()
    dirOpt.toSeq.filter(os.isDir(_)).flatMap { dir =>
      os.list(dir)
        .filter(_.last.startsWith(libraryName0 + "-"))
        .filter(os.isFile(_))
        .map { f =>
          val name = f.last.stripPrefix(libraryName0 + "-")
          val idx = name.lastIndexOf('.')
          assert(idx >= 0, s"no extension found in $f name")
          val classifier = name.take(idx)
          val ext = name.drop(idx + 1)
          PublishInfo(
            file = PathRef(f),
            ivyConfig = "compile",
            classifier = Some(classifier),
            ext = ext,
            ivyType = ext
          )
        }
    }
  }

  def extraPublish =
    if (jniAddThisBuildArtifacts) {
      if (Properties.isWin)
        T {
          super.extraPublish() ++ windowsExtraPublish() ++ jniArtifactDirExtraPublish()
        }
      else
        T {
          super.extraPublish() ++ unixExtraPublish() ++ jniArtifactDirExtraPublish()
        }
    } else
      T {
        jniArtifactDirExtraPublish()
      }
}

trait JniWindowsModule extends Module {

  def windowsDllName: T[String]
  def windowsLinkingLibs = T{ Seq.empty[String] }

  def windowsJavaHome = T {
    val dir = os.Path(sys.props("java.home"), os.pwd)
    // Seems required with Java 8
    if (dir.last == "jre") dir / os.up
    else dir
  }

  def windowsCSources = T.sources {
    Seq(PathRef(millSourcePath / "src" / "main" / "c"))
  }

  def windowsCOptions = T{ Seq.empty[String] }
  def windowsDllCOptions = T{ Seq.empty[String] }

  def windowsCompile = T.persistent {
    val destDir = T.ctx().dest / "obj"
    val cFiles = windowsCSources().flatMap { dir =>
      if (os.isDir(dir.path))
        os.walk(dir.path)
          .filter(p => os.isFile(p) && p.last.endsWith(".c"))
      else
        Nil
    }
    val javaHome0 = windowsJavaHome()
    for (f <- cFiles) yield {
      if (!os.exists(destDir))
        os.makeDir.all(destDir)
      val path = f.relativeTo(os.pwd).toString
      val output = destDir / s"${f.last.stripSuffix(".c")}.obj"
      val needsUpdate = !os.isFile(output) || os.mtime(output) < os.mtime(f)
      if (needsUpdate) {
        val script =
         s"""@call "$vcvars"
            |if %errorlevel% neq 0 exit /b %errorlevel%
            |cl /I $q${javaHome0 / "include"}$q /I $q${javaHome0 / "include" / "win32"}$q /utf-8 /c $q$f$q
            |""".stripMargin
        val scriptPath = T.dest / "run-cl.bat"
        os.write.over(scriptPath, script.getBytes, createFolders = true)
        os.proc(scriptPath).call(cwd = destDir, stdout = os.Inherit)
      }
      PathRef(output.resolveFrom(os.pwd))
    }
  }

  def windowsDll = T.persistent {
    val dllName0 = windowsDllName()
    val destDir = T.ctx().dest / "dlls"
    if (!os.exists(destDir))
      os.makeDir.all(destDir)
    val dest = destDir / s"$dllName0.dll"
    val relDest = dest.relativeTo(os.pwd)
    val objs = windowsCompile()
    val objsArgs = objs.map(o => o.path.relativeTo(os.pwd).toString).distinct
    val libsArgs = windowsLinkingLibs().map(l => l + ".lib")
    val needsUpdate = !os.isFile(dest) || {
      val destMtime = os.mtime(dest)
      objs.exists(o => os.mtime(o.path) > destMtime)
    }
    if (needsUpdate) {
      val script =
       s"""@call "$vcvars"
          |if %errorlevel% neq 0 exit /b %errorlevel%
          |link /DLL "/OUT:$dest" ${libsArgs.mkString(" ")} ${objs.map(f => "\"" + f.path.toString + "\"").mkString(" ")}
          |""".stripMargin
      val scriptPath = T.dest / "run-cl.bat"
      os.write.over(scriptPath, script.getBytes, createFolders = true)
      os.proc(scriptPath).call(cwd = T.dest, stdout = os.Inherit)
    }
    PathRef(dest)
  }

  def windowsLib = T {
    val fileName = windowsDllName() + ".lib"
    val allObjFiles = windowsCompile().map(_.path)
    val output = T.dest / fileName
    val libNeedsUpdate = !os.isFile(output) || allObjFiles.exists(f => os.mtime(output) < os.mtime(f))
    if (libNeedsUpdate) {
      val script =
       s"""@call "$vcvars"
          |if %errorlevel% neq 0 exit /b %errorlevel%
          |lib "/out:$fileName" ${allObjFiles.map(f => "\"" + f.toString + "\"").mkString(" ")}
          |""".stripMargin
      val scriptPath = T.dest / "run-lib.bat"
      os.write.over(scriptPath, script.getBytes, createFolders = true)
      os.proc(scriptPath).call(cwd = T.dest, stdout = os.Inherit)
      if (!os.isFile(output))
        sys.error(s"Error: $output not created")
    }
    PathRef(output)
  }
}

trait JniWindowsAddResources extends JavaModule with JniWindowsModule {
  def windowsResources = T.sources {
    val dll0 = windowsDll().path
    val dir = T.ctx().dest / "dll-resources"
    val dllDir = dir / "META-INF" / "native" / "windows64"
    val dest = dllDir / dll0.last
    val needsUpdate = !os.isFile(dest) || {
      val content = os.read.bytes(dest)
      val newContent = os.read.bytes(dll0)
      !Arrays.equals(content, newContent)
    }
    if (needsUpdate)
      os.copy(dll0, dest, replaceExisting = true, createFolders = true)
    Seq(PathRef(dir))
  }
  def resources =
    if (Properties.isWin)
      T.sources {
        super.resources() ++ windowsResources()
      }
    else
      super.resources
}

trait JniWindowsPublish extends JniWindowsModule {
  def windowsExtraPublish = T{
    Seq(
      PublishInfo(
        file = windowsDll(),
        ivyConfig = "compile",
        classifier = Some("x86_64-pc-win32"),
        ext = "dll",
        ivyType = "dll"
      ),
      PublishInfo(
        file = windowsLib(),
        ivyConfig = "compile",
        classifier = Some("x86_64-pc-win32"),
        ext = "lib",
        ivyType = "lib"
      )
    )
  }
}

trait JniUnixModule extends Module {

  def unixLibName: T[String]

  def unixCSources = T.sources {
    Seq(PathRef(millSourcePath / "src" / "main" / "c"))
  }

  def unixGcc = T{
    Seq("gcc")
  }

  def unixCOptions = T{ Seq.empty[String] }
  def unixLinkingLibs = T{ Seq.empty[String] }

  def unixJavaHome = T{
    import java.io.File
    val value = sys.props("java.home")
    val dir = new File(value)
    // Seems required with Java 8
    if (dir.getName == "jre") dir.getParent
    else value
  }

  def osDirName = T{
    if (Properties.isLinux) "linux"
    else if (Properties.isMac) "darwin"
    else sys.error("Unrecognized OS")
  }

  def unixExtension = T{
    if (Properties.isLinux) "so"
    else if (Properties.isMac) "dylib"
    else sys.error("Unrecognized OS")
  }

  private def unixDoCompile(
    destDir: os.Path,
    unixCSources: Seq[PathRef],
    javaHome0: String,
    gcc0: Seq[String],
    extraOpts: Seq[String],
    osDirName0: String
  ): Seq[PathRef] = {
    val cFiles = unixCSources.flatMap { dir =>
      if (os.isDir(dir.path))
        os.walk(dir.path)
          .filter(p => os.isFile(p) && p.last.endsWith(".c"))
      else
        Nil
    }
    for (f <- cFiles) yield {
      if (!os.exists(destDir))
        os.makeDir.all(destDir)
      val path = f.relativeTo(os.pwd).toString
      val output = destDir / s"${f.last.stripSuffix(".c")}.o"
      val needsUpdate = !os.isFile(output) || os.mtime(output) < os.mtime(f)
      if (needsUpdate) {
        val relOutput = output.relativeTo(os.pwd)
        val command = gcc0 ++ Seq("-c", "-Wall", "-fPIC", s"-I$javaHome0/include", s"-I$javaHome0/include/$osDirName0") ++ extraOpts ++ Seq(path, "-o", relOutput.toString)
        System.err.println(s"Running ${command.mkString(" ")}")
        val res = os
          .proc(command.map(x => x: os.Shellable): _*)
          .call(stdin = os.Inherit, stdout = os.Inherit, stderr = os.Inherit)
        if (res.exitCode != 0)
          sys.error(s"${gcc0.mkString(" ")} command exited with code ${res.exitCode}")
      }
      PathRef(output.resolveFrom(os.pwd))
    }
  }

  def unixCompile =
    T.persistent {
      val destDir = T.ctx().dest / "obj"
      val javaHome0 = unixJavaHome()
      val gcc0 = unixGcc()
      val extraOpts = unixCOptions()
      val osDirName0 = osDirName()
      unixDoCompile(
        destDir,
        unixCSources(),
        javaHome0,
        gcc0,
        extraOpts,
        osDirName0
      )
    }

  def macosX86_64Compile =
    T.persistent {
      val destDir = T.ctx().dest / "obj"
      val javaHome0 = unixJavaHome()
      val gcc0 = unixGcc()
      val extraOpts = unixCOptions() ++ Seq("-arch", "x86_64")
      val osDirName0 = osDirName()
      unixDoCompile(
        destDir,
        unixCSources(),
        javaHome0,
        gcc0,
        extraOpts,
        osDirName0
      )
    }

  def macosArm64Compile =
    T.persistent {
      val destDir = T.ctx().dest / "obj"
      val javaHome0 = unixJavaHome()
      val gcc0 = unixGcc()
      val extraOpts = unixCOptions() ++ Seq("-arch", "arm64")
      val osDirName0 = osDirName()
      unixDoCompile(
        destDir,
        unixCSources(),
        javaHome0,
        gcc0,
        extraOpts,
        osDirName0
      )
    }

  private def generateUnixSo(
    dllName0: String,
    unixExtension0: String,
    extraOpts: Seq[String],
    objs: Seq[PathRef],
    destDir: os.Path,
    unixLinkingLibs: Seq[String],
    gcc0: Seq[String]
  ) = {
    if (!os.exists(destDir))
      os.makeDir.all(destDir)
    val dest = destDir / s"$dllName0.$unixExtension0"
    val relDest = dest.relativeTo(os.pwd)
    val objsArgs = objs.map(o => o.path.relativeTo(os.pwd).toString).distinct
    val libsArgs = unixLinkingLibs.map(l => "-l" + l)
    val needsUpdate = !os.isFile(dest) || {
      val destMtime = os.mtime(dest)
      objs.exists(o => os.mtime(o.path) > destMtime)
    }
    if (needsUpdate) {
      val command = gcc0 ++ Seq("-shared") ++ extraOpts ++ Seq("-o", relDest.toString) ++ objsArgs ++ libsArgs
      System.err.println(s"Running ${command.mkString(" ")}")
      val res = os
        .proc(command.map(x => x: os.Shellable): _*)
        .call(stdin = os.Inherit, stdout = os.Inherit, stderr = os.Inherit)
      if (res.exitCode != 0)
        sys.error(s"${gcc0.mkString(" ")} command exited with code ${res.exitCode}")
    }
    PathRef(dest)
  }
  def unixSo =
    if (Properties.isMac)
      T.persistent {
        val dllName0 = unixLibName()
        val unixExtension0 = unixExtension()
        val x86Objs = macosX86_64Compile()
        val armObjs = macosArm64Compile()
        val destDir = T.dest / "libs"
        val gcc0 = unixGcc()
        val dest = destDir / s"$dllName0.dylib"
        val x86DyLib = generateUnixSo(
          dllName0,
          unixExtension0,
          Seq("-arch", "x86_64"),
          x86Objs,
          destDir / "x86_64",
          unixLinkingLibs(),
          gcc0
        )
        val armDyLib = generateUnixSo(
          dllName0,
          unixExtension0,
          Seq("-arch", "arm64"),
          armObjs,
          destDir / "arm64",
          unixLinkingLibs(),
          gcc0
        )
        os.proc("lipo", "-create", "-o", dest, x86DyLib.path, armDyLib.path)
          .call(cwd = destDir, stdout = os.Inherit)
        PathRef(dest)
      }
    else
      T.persistent {
        val dllName0 = unixLibName()
        val unixExtension0 = unixExtension()
        val objs = unixCompile()
        val destDir = T.dest / "libs"
        val gcc0 = unixGcc()
        generateUnixSo(
          dllName0,
          unixExtension0,
          Nil,
          objs,
          destDir,
          unixLinkingLibs(),
          gcc0
        )
      }
  private def buildUnixA(
    dllName0: String,
    destDir: os.Path,
    objs: Seq[PathRef]
  ) = {
    if (!os.exists(destDir))
      os.makeDir.all(destDir)
    val dest = destDir / s"$dllName0.a"
    val relDest = dest.relativeTo(os.pwd)
    val objsArgs = objs.map(o => o.path.relativeTo(os.pwd).toString).distinct
    val needsUpdate = !os.isFile(dest) || {
      val destMtime = os.mtime(dest)
      objs.exists(o => os.mtime(o.path) > destMtime)
    }
    if (needsUpdate) {
      val command = Seq("ar", "rcs", relDest.toString) ++ objsArgs
      System.err.println(s"Running ${command.mkString(" ")}")
      val res = os
        .proc(command.map(x => x: os.Shellable): _*)
        .call(stdin = os.Inherit, stdout = os.Inherit, stderr = os.Inherit)
      if (res.exitCode != 0)
        sys.error(s"${command.mkString(" ")} exited with code ${res.exitCode}")
    }
    PathRef(dest)
  }
  def unixA =
    if (Properties.isMac)
      T.persistent {
        val dllName0 = unixLibName()
        val destDir = T.ctx().dest / "libs"
        val x86Objs = macosX86_64Compile()
        val armObjs = macosArm64Compile()
        val x86A = buildUnixA(
          dllName0,
          destDir / "x86_64",
          x86Objs
        )
        val armA = buildUnixA(
          dllName0,
          destDir / "arm64",
          armObjs
        )
        val a = destDir / s"$dllName0.a"
        os.proc("lipo", "-create", "-o", a, x86A.path, armA.path)
          .call(cwd = destDir, stdout = os.Inherit)
        PathRef(a)
      }
    else
      T.persistent {
        val dllName0 = unixLibName()
        val destDir = T.ctx().dest / "libs"
        val objs = unixCompile()
        buildUnixA(
          dllName0,
          destDir,
          objs
        )
      }
}

trait JniUnixAddResources extends JavaModule with JniUnixModule {
  def unixResources = T.sources {
    val dll0 = unixSo().path
    val dir = T.ctx().dest / "so-resources"
    val osName = osDirName() match {
      case "linux" => "linux64"
      case other => other
    }
    val dllDir = dir / "META-INF" / "native" / osName
    val dest = dllDir / dll0.last
    val needsUpdate = !os.isFile(dest) || {
      val content = os.read.bytes(dest)
      val newContent = os.read.bytes(dll0)
      !Arrays.equals(content, newContent)
    }
    if (needsUpdate)
      os.copy(dll0, dest, replaceExisting = true, createFolders = true)
    Seq(PathRef(dir))
  }
  def resources =
    if (Properties.isLinux || Properties.isMac)
      T.sources {
        super.resources() ++ unixResources()
      }
    else
      super.resources
}

trait JniUnixPublish extends JniUnixModule {
  def osClassifier = T{
    if (Properties.isMac) "x86_64-apple-darwin"
    else if (Properties.isLinux) "x86_64-pc-linux"
    else sys.error("Unrecognized OS")
  }
  def unixExtraPublish = T{
    Seq(
      PublishInfo(
        file = unixSo(),
        ivyConfig = "compile",
        classifier = Some(osClassifier()),
        ext = unixExtension(),
        ivyType = unixExtension()
      ),
      PublishInfo(
        file = unixA(),
        ivyConfig = "compile",
        classifier = Some(osClassifier()),
        ext = "a",
        ivyType = "a"
      )
    )
  }
}
