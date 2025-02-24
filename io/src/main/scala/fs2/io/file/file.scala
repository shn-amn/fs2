package fs2
package io

import scala.concurrent.duration._

import java.nio.file.{Path, StandardOpenOption, WatchEvent}

import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Sync, Timer}

/** Provides support for working with files. */
package object file {

  /**
    * Reads all data synchronously from the file at the specified `java.nio.file.Path`.
    */
  def readAll[F[_]: Sync: ContextShift](path: Path, blocker: Blocker, chunkSize: Int)(
      ): Stream[F, Byte] =
    pulls
      .fromPath(path, blocker, List(StandardOpenOption.READ))
      .flatMap(c => pulls.readAllFromFileHandle(chunkSize)(c.resource))
      .stream

  /**
    * Reads a range of data synchronously from the file at the specified `java.nio.file.Path`.
    * `start` is inclusive, `end` is exclusive, so when `start` is 0 and `end` is 2,
    * two bytes are read.
    */
  def readRange[F[_]: Sync: ContextShift](path: Path,
                                          blocker: Blocker,
                                          chunkSize: Int,
                                          start: Long,
                                          end: Long)(
      ): Stream[F, Byte] =
    pulls
      .fromPath(path, blocker, List(StandardOpenOption.READ))
      .flatMap(c => pulls.readRangeFromFileHandle(chunkSize, start, end)(c.resource))
      .stream

  /**
    * Reads all data synchronously from the file at the specified `java.nio.file.Path`,
    * starting at `offset` position.
    * Then waits for `delay` duration and reads whatever has been added to the file
    * in the mean time. Repeats from delay.
    */
  def tail[F[_]: Sync: ContextShift: Timer](path: Path,
                                            blocker: Blocker,
                                            chunkSize: Int,
                                            offset: Long = 0L,
                                            pollDelay: FiniteDuration = 1.second)(
      ): Stream[F, Byte] =
    pulls
      .fromPath(path, blocker, StandardOpenOption.READ :: Nil)
      .flatMap(c => pulls.tailFromFileHandle(chunkSize, offset, pollDelay)(c.resource))
      .stream

  /**
    * Writes all data synchronously to the file at the specified `java.nio.file.Path`.
    *
    * Adds the WRITE flag to any other `OpenOption` flags specified. By default, also adds the CREATE flag.
    */
  def writeAll[F[_]: Sync: ContextShift](
      path: Path,
      blocker: Blocker,
      flags: Seq[StandardOpenOption] = List(StandardOpenOption.CREATE)
  ): Pipe[F, Byte, Unit] =
    in =>
      (for {
        out <- pulls.fromPath(path, blocker, StandardOpenOption.WRITE :: flags.toList)
        fileHandle = out.resource
        offset <- if (flags.contains(StandardOpenOption.APPEND)) Pull.eval(fileHandle.size)
        else Pull.pure(0L)
        _ <- pulls.writeAllToFileHandleAtOffset(in, fileHandle, offset)
      } yield ()).stream

  private def _writeAll0[F[_]](in: Stream[F, Byte],
                               out: FileHandle[F],
                               offset: Long): Pull[F, Nothing, Unit] =
    in.pull.uncons.flatMap {
      case None => Pull.done
      case Some((hd, tl)) =>
        _writeAll1(hd, out, offset) >> _writeAll0(tl, out, offset + hd.size)
    }

  private def _writeAll1[F[_]](buf: Chunk[Byte],
                               out: FileHandle[F],
                               offset: Long): Pull[F, Nothing, Unit] =
    Pull.eval(out.write(buf, offset)).flatMap { (written: Int) =>
      if (written >= buf.size)
        Pull.pure(())
      else
        _writeAll1(buf.drop(written), out, offset + written)
    }

  /**
    * Creates a [[Watcher]] for the default file system.
    *
    * A singleton bracketed stream is returned consisting of the single watcher. To use the watcher,
    * `flatMap` the returned stream, watch or register 1 or more paths, and then return `watcher.events()`.
    *
    * @return singleton bracketed stream returning a watcher
    */
  def watcher[F[_]: Concurrent: ContextShift](blocker: Blocker): Resource[F, Watcher[F]] =
    Watcher.default(blocker)

  /**
    * Watches a single path.
    *
    * Alias for creating a watcher and watching the supplied path, releasing the watcher when the resulting stream is finalized.
    */
  def watch[F[_]: Concurrent: ContextShift](
      blocker: Blocker,
      path: Path,
      types: Seq[Watcher.EventType] = Nil,
      modifiers: Seq[WatchEvent.Modifier] = Nil,
      pollTimeout: FiniteDuration = 1.second): Stream[F, Watcher.Event] =
    Stream
      .resource(Watcher.default(blocker))
      .flatMap(w => Stream.eval_(w.watch(path, types, modifiers)) ++ w.events(pollTimeout))
}
