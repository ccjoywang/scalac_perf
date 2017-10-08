package scala.tools.nsc.backend.jvm

import java.io.{DataOutputStream, IOException}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.jar.Attributes.Name

import scala.reflect.internal.util.{NoPosition, Statistics}
import scala.reflect.io._
import scala.tools.nsc.backend.jvm.BTypes.InternalName
import scala.tools.nsc.io.{AbstractFile, Jar, JarWriter}
//
//object ClassFileWriter {
//  private def getDirectoryOrNull(dir: Option[String]): AbstractFile =
//    dir.map(d => new PlainDirectory(Directory(Path(d)))).orNull
//
//  def apply(frontendAccess: PostProcessorFrontendAccess): ClassfileWriter = {
//    import frontendAccess.{backendReporting, compilerSettings}
//    import ClassFileWriter._
//    // if non-null, asm text files are written to this directory
//    val asmOutputDir: AbstractFile = getDirectoryOrNull(compilerSettings.genAsmpDirectory)
//
//    // if non-null, classfiles are additionally written to this directory
//    val dumpOutputDir: AbstractFile = getDirectoryOrNull(compilerSettings.dumpClassesDirectory)
//
//    def singleWriter(file: AbstractFile): ClassfileWriter = {
//      if (file hasExtension "jar") {
//        // If no main class was specified, see if there's only one
//        // entry point among the classes going into the jar.
//        val mainClass = compilerSettings.mainClass match {
//          case c@Some(m) =>
//            backendReporting.log(s"Main-Class was specified: $m")
//            c
//
//          case None => frontendAccess.getEntryPoints match {
//            case Nil =>
//              backendReporting.log("No Main-Class designated or discovered.")
//              None
//            case name :: Nil =>
//              backendReporting.log(s"Unique entry point: setting Main-Class to $name")
//              Some(name)
//            case names =>
//              backendReporting.log(s"No Main-Class due to multiple entry points:\n  ${names.mkString("\n  ")}")
//              None
//          }
//        }
//        val jarMainAttrs = mainClass.map(c => Name.MAIN_CLASS -> c).toList
//        val jar = new Jar(file.file).jarWriter(jarMainAttrs: _*)
//        new JarClassWriter(frontendAccess, jar)
//      } else if (file.isVirtual) {
//        ???
//      } else if (file.isDirectory) {
//        new DirClassWriter(frontendAccess, file.file.toPath)
//      } else {
//        ???
//      }
//    }
//  }
//  private final class JarClassWriter(val frontendAccess: PostProcessorFrontendAccess, jarWriter:scala.tools.nsc.io.JarWriter) extends ClassfileWriter {
//    override def ClassFileWriterFor(sourceFile: AbstractFile): Unit = this
//    override def write(className: InternalName, bytes: Array[Byte], sourceFile: AbstractFile): Unit = {
//      val path = className + ".class"
//      val out = jarWriter.newOutputStream(path)
//      try out.write(bytes, 0, bytes.length)
//      finally out.flush()
//    }
//  }
//  private final class DirClassWriter(val frontendAccess: PostProcessorFrontendAccess, root: Path) extends ClassfileWriter {
//
//    override def ClassFileWriterFor(sourceFile: AbstractFile): Unit = this
//
//    private def getDirectoryOrNull(dir: Option[String]): AbstractFile =
//      dir.map(d => new PlainDirectory(Directory(Path(d)))).orNull
//
//    private def fastGetFile(base: AbstractFile, clsName: String, suffix: String) = {
//      val index = clsName.lastIndexOf('/')
//      val (packageName, simpleName) = if (index > 0) {
//        (clsName.substring(0, index), clsName.substring(index + 1))
//      } else ("", clsName)
//      val directory = base.file.toPath.resolve(packageName)
//      new PlainNioFile(directory.resolve(simpleName + suffix))
//    }
//
//    private def getFile(base: AbstractFile, clsName: String, suffix: String): AbstractFile = {
//      if (base.file != null) {
//        fastGetFile(base, clsName, suffix)
//      } else {
//        def ensureDirectory(dir: AbstractFile): AbstractFile =
//          if (dir.isDirectory) dir
//          else throw new FileConflictException(s"${base.path}/$clsName$suffix: ${dir.path} is not a directory", dir)
//        var dir = base
//        val pathParts = clsName.split("[./]").toList
//        for (part <- pathParts.init) dir = ensureDirectory(dir) subdirectoryNamed part
//        ensureDirectory(dir) fileNamed pathParts.last + suffix
//      }
//    }
//
//    override def write(className: InternalName, bytes: Array[Byte], sourceFile: AbstractFile): Unit = {
//      val outFolder = compilerSettings.outputDirectoryFor(sourceFile)
//      val outFile = getFile(outFolder, className, ".class")
//      writeBytes(outFile, bytes)
//
//    }
//  }
//  private final class MultiClassWriter(val frontendAccess: PostProcessorFrontendAccess) extends ClassfileWriter {
//    import frontendAccess.{backendReporting, compilerSettings}
//    val realWriter: Map [AbstractFile, ClassfileWriter] = ???
//    override def ClassFileWriterFor(sourceFile: AbstractFile): Unit = frontendAccess.frontendLock.synchronized{
//      realWriter(frontendAccess.compilerSettings.outputDirectoryFor(sourceFile))
//    }
//
//
//  }
//}
//sealed trait ClassfileWriter {
//  def classFileWriterFor(sourceFile: AbstractFile): UnderlyingClassfileWriter
//}
//sealed trait UnderlyingClassfileWriter {
//  val frontendAccess: PostProcessorFrontendAccess
//
//  import frontendAccess.{backendReporting, compilerSettings}
//
//  def write(className: InternalName, bytes: Array[Byte], sourceFile: AbstractFile): Unit
//}

class ClassfileWriter(frontendAccess: PostProcessorFrontendAccess) {
  import frontendAccess.{backendReporting, compilerSettings}

  // if non-null, asm text files are written to this directory
  private val asmOutputDir: AbstractFile = getDirectoryOrNull(compilerSettings.genAsmpDirectory)

  // if non-null, classfiles are additionally written to this directory
  private val dumpOutputDir: AbstractFile = getDirectoryOrNull(compilerSettings.dumpClassesDirectory)

  // if non-null, classfiles are written to a jar instead of the output directory
  private val jarWriter: JarWriter = compilerSettings.singleOutputDirectory match {
    case Some(f) if f hasExtension "jar" =>
      // If no main class was specified, see if there's only one
      // entry point among the classes going into the jar.
      val mainClass = compilerSettings.mainClass match {
        case c @ Some(m) =>
          backendReporting.log(s"Main-Class was specified: $m")
          c

        case None => frontendAccess.getEntryPoints match {
          case Nil =>
            backendReporting.log("No Main-Class designated or discovered.")
            None
          case name :: Nil =>
            backendReporting.log(s"Unique entry point: setting Main-Class to $name")
            Some(name)
          case names =>
            backendReporting.log(s"No Main-Class due to multiple entry points:\n  ${names.mkString("\n  ")}")
            None
        }
      }
      val jarMainAttrs = mainClass.map(c => Name.MAIN_CLASS -> c).toList
      new Jar(f.file).jarWriter(jarMainAttrs: _*)

    case _ => null
  }

  private def getDirectoryOrNull(dir: Option[String]): AbstractFile =
    dir.map(d => new PlainDirectory(Directory(Path(d)))).orNull

  private def getFile(base: AbstractFile, clsName: String, suffix: String): AbstractFile = {
    if (base.file != null) {
      fastGetFile(base, clsName, suffix)
    } else {
      def ensureDirectory(dir: AbstractFile): AbstractFile =
        if (dir.isDirectory) dir
        else throw new FileConflictException(s"${base.path}/$clsName$suffix: ${dir.path} is not a directory", dir)
      var dir = base
      val pathParts = clsName.split("[./]").toList
      for (part <- pathParts.init) dir = ensureDirectory(dir) subdirectoryNamed part
      ensureDirectory(dir) fileNamed pathParts.last + suffix
    }
  }

  private def fastGetFile(base: AbstractFile, clsName: String, suffix: String) = {
    val index = clsName.lastIndexOf('/')
    val (packageName, simpleName) = if (index > 0) {
      (clsName.substring(0, index), clsName.substring(index + 1))
    } else ("", clsName)
    val directory = base.file.toPath.resolve(packageName)
    new PlainNioFile(directory.resolve(simpleName + suffix))
  }

  private def writeBytes(outFile: AbstractFile, bytes: Array[Byte]): Unit = {
    if (outFile.file != null) {
      val outPath = outFile.file.toPath
      try Files.write(outPath, bytes)
      catch {
        case _: java.nio.file.NoSuchFileException =>
          Files.createDirectories(outPath.getParent)
          Files.write(outPath, bytes)
      }
    } else {
      val out = new DataOutputStream(outFile.bufferedOutput)
      try out.write(bytes, 0, bytes.length)
      finally out.close()
    }
  }

  def write(unitResult: SourceUnit, generatedClass:GeneratedClass, className: InternalName, bytes: Array[Byte]): Unit = try {
    val writeStart = Statistics.startTimer(BackendStats.bcodeWriteTimer)
    if (jarWriter == null) {
      val outFolder = unitResult.outputDir
      val outFile = getFile(outFolder, className, ".class")
      writeBytes(outFile, bytes)
    } else {
      val path = className + ".class"
      val out = jarWriter.newOutputStream(path)
      try out.write(bytes, 0, bytes.length)
      finally out.flush()
    }
    Statistics.stopTimer(BackendStats.bcodeWriteTimer, writeStart)

    if (asmOutputDir != null) {
      val asmpFile = getFile(asmOutputDir, className, ".asmp")
      val asmpString = AsmUtils.textify(AsmUtils.readClass(bytes))
      writeBytes(asmpFile, asmpString.getBytes(StandardCharsets.UTF_8))
    }

    if (dumpOutputDir != null) {
      val dumpFile = getFile(dumpOutputDir, className, ".class")
      writeBytes(dumpFile, bytes)
    }
  } catch {
    case e: FileConflictException =>
      backendReporting.error(NoPosition, s"error writing $className: ${e.getMessage}")
    case e: java.nio.file.FileSystemException =>
      if (compilerSettings.debug)
        e.printStackTrace()
      backendReporting.error(NoPosition, s"error writing $className: ${e.getClass.getName} ${e.getMessage}")
  }

  def close(): Unit = {
    if (jarWriter != null) jarWriter.close()
  }
}

/** Can't output a file due to the state of the file system. */
class FileConflictException(msg: String, val file: AbstractFile) extends IOException(msg)
