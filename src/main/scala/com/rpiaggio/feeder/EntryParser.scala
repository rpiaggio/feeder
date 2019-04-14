package com.rpiaggio.feeder

import java.nio.charset.StandardCharsets

import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.util.ByteString
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}

import scala.collection.mutable

class EntryParser(pattern: ParsePattern) extends GraphStage[FlowShape[ByteString, EntryData]] {
  val input = Inlet[ByteString]("EntryParser.in")
  val output = Outlet[EntryData]("EntryParser.out")

  override def shape: FlowShape[ByteString, EntryData] = FlowShape(input, output)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {

    var entryRemainingInstructions: Option[Seq[ParseInstruction]] = None

    var currentEntry: EntryData = Seq.empty[String]

    var entryQueue = mutable.Queue.empty[EntryData]

    var previousBuffer: ByteString = ByteString.empty

    setHandler(input, new InHandler {
      override def onPush(): Unit = {
        val bytes = previousBuffer ++ grab(input)

        def parseChunk(lastIndex: Int = 0): Unit = {
          val nextString = entryRemainingInstructions.fold(pattern.start)(_.head.until)
          val nextIndex = bytes.indexOfSlice(nextString.getBytes(StandardCharsets.UTF_8), lastIndex)

          if (nextIndex >= 0) {
            entryRemainingInstructions.fold {
              entryRemainingInstructions = Some(pattern.instructions)
              parseChunk(nextIndex + pattern.start.length)
            } { instructions =>
              val currentInstruction = instructions.head
              if (currentInstruction.action == ParseAction.Capture) {
                currentEntry = currentEntry :+ new String(bytes.slice(lastIndex, nextIndex).toArray, StandardCharsets.UTF_8).replaceAll("\n\r", "")
              }

              val remainingInstructions = instructions.tail
              if (remainingInstructions.isEmpty) {
                if (isAvailable(output)) push(output, currentEntry) else entryQueue.enqueue(currentEntry)
                entryRemainingInstructions = None
                currentEntry = Seq.empty[String]
              } else {
                entryRemainingInstructions = Some(remainingInstructions)
              }

              parseChunk(nextIndex + currentInstruction.until.length)
            }
          } else {
            previousBuffer = bytes.drop(lastIndex)
          }
        }

        parseChunk()
        if (!hasBeenPulled(input)) pull(input)
      }
    })

    setHandler(output, new OutHandler {
      override def onPull() = {
        if (entryQueue.nonEmpty) {
          push(output, entryQueue.dequeue)
        }
        if (!hasBeenPulled(input)) pull(input)
      }
    })
  }
}
