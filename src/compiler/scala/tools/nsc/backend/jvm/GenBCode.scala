/* NSC -- new Scala compiler
 * Copyright 2005-2012 LAMP/EPFL
 * @author  Martin Odersky
 */

package scala.tools.nsc
package backend
package jvm

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.reflect.internal.util.Statistics
import scala.tools.asm.Opcodes
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

abstract class GenBCode extends SubComponent {
  self =>
  import global._

  val postProcessorFrontendAccess: PostProcessorFrontendAccess = new PostProcessorFrontendAccess.PostProcessorFrontendAccessImpl(global)

  val bTypes: BTypesFromSymbols[global.type] = new { val frontendAccess = postProcessorFrontendAccess } with BTypesFromSymbols[global.type](global)

  val codeGen: CodeGen[global.type] = new { val bTypes: self.bTypes.type = self.bTypes } with CodeGen[global.type](global)

  val postProcessor: PostProcessor { val bTypes: self.bTypes.type } = new { val bTypes: self.bTypes.type = self.bTypes } with PostProcessor

  val phaseName = "jvm"

  override def newPhase(prev: Phase) = new BCodePhase(prev)


  class BCodePhase(prev: Phase) extends StdPhase(prev) {
    override def description = "Generate bytecode from ASTs using the ASM library"

    override val erasedTypes = true

    private var generatedHandler:ClassHandler = _
    def apply(unit: CompilationUnit): Unit = {
      codeGen.genUnit(unit, generatedHandler)
    }

    override def run(): Unit = {
      BackendStats.timed(BackendStats.bcodeTimer) {
        try {
          initialize()
          val writer = postProcessor.classfileWriter.get
          BackendStats.timed(BackendStats.bcodeGenStat) {
            super.run() // invokes `apply` for each compilation unit
          }
          generatedHandler.globalOptimise()
          // This way it is easier to test, as the results are deterministic
          // the the loss of potential performance is probably minimal
          generatedHandler.pending().foreach {
            unitResult: UnitResult =>
              try {
                Await.result(unitResult.task, Duration.Inf)
                Await.result(unitResult.result.future, Duration.Inf)
              } catch {
                case NonFatal(t) =>
                  t.printStackTrace
                  postProcessorFrontendAccess.backendReporting.error(NoPosition, s"unable to write ${unitResult.source} $t")
              }
          }
        } finally {
          // When writing to a jar, we need to close the jarWriter. Since we invoke the postProcessor
          // multiple times if (!globalOptsEnabled), we have to do it here at the end.
          postProcessor.classfileWriter.get.close()
        }
      }
    }

    /**
     * Several backend components have state that needs to be initialized in each run, because
     * it depends on frontend data that may change between runs: Symbols, Types, Settings.
     */
    private def initialize(): Unit = {
      val initStart = Statistics.startTimer(BackendStats.bcodeInitTimer)
      scalaPrimitives.init()
      bTypes.initialize()
      codeGen.initialize()
      postProcessorFrontendAccess.initialize()
      postProcessor.initialize()
      generatedHandler = ClassHandler(settings, postProcessor)
      Statistics.stopTimer(BackendStats.bcodeInitTimer, initStart)
    }
  }
}

object GenBCode {
  def mkFlags(args: Int*) = args.foldLeft(0)(_ | _)

  final val PublicStatic = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC
  final val PublicStaticFinal = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL

  val CLASS_CONSTRUCTOR_NAME = "<clinit>"
  val INSTANCE_CONSTRUCTOR_NAME = "<init>"
}
