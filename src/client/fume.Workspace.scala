                                                                                                  /*
┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
┃                                                                                                  ┃
┃                          ╭────────╮                                                              ┃
┃                          │   ╭────╯                                                              ┃
┃                          │   │                                                                   ┃
┃                          │   ╰──╮╭───╮ ╭───╮╭───╮╌────╮╌────╮╭────────╮                          ┃
┃                          │   ╭──╯│   │ │   ││   ╭─╮   ╭─╮   ││   ╭─╮  │                          ┃
┃                          │   │   │   │ │   ││   │ │   │ │   ││   ╰─╯  │                          ┃
┃                          │   │   │   │ │   ││   │ │   │ │   ││   ╭────╯                          ┃
┃                          │   │   │   ╰─╯   ││   │ │   │ │   ││   ╰────╮                          ┃
┃                          ╰───╯   ╰────╌╰───╯╰───╯ ╰───╯ ╰───╯╰────────╯                          ┃
┃                                                                                                  ┃
┃    Fume, version 0.1.0.                                                                          ┃
┃    © Copyright 2026 Jon Pretty, Propensive OÜ.                                                   ┃
┃                                                                                                  ┃
┃    The primary distribution site is:                                                             ┃
┃                                                                                                  ┃
┃        https://propensive.dev/fume/                                                              ┃
┃                                                                                                  ┃
┃    Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file     ┃
┃    except in compliance with the License. You may obtain a copy of the License at                ┃
┃                                                                                                  ┃
┃        https://www.apache.org/licenses/LICENSE-2.0                                               ┃
┃                                                                                                  ┃
┃    Unless required by applicable law or agreed to in writing,  software distributed under the    ┃
┃    License is distributed on an "AS IS" BASIS,  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,    ┃
┃    either express or implied. See the License for the specific language governing permissions    ┃
┃    and limitations under the License.                                                            ┃
┃                                                                                                  ┃
┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛
                                                                                                  */
package fume

import scala.collection.concurrent.TrieMap

import soundness.*

import filesystemBackends.virtualMachineFilesystem

// The per-project workspace: a `.fume` directory in the invocation's working directory or the
// nearest ancestor holding one — resolved upwards exactly like `.git`, so `fume` can be invoked
// from anywhere inside a project. Its `config.tel` file is a TEL document of settings.
//
// The SCHEMA of `config.tel` is the settings themselves: each `Setting`'s camelCase name maps
// to a kebab-case TEL keyword (the same derivation as its `--flag`), so a setting declared in
// `fume_client.scala` is a configuration key here with no further wiring. Three value rules:
//
//   classpath out/tests.jar     # a keyword with an atom: the setting's value is that atom
//   fail-fast                   # a bare keyword (a TEL flag): reads as `true`
//   classpath out/util.jar      # a REPEATED keyword: the atoms join with ':', so a multi-entry
//                               # classpath is written one entry per line
//
// Because fume runs as a daemon, parsed configurations are cached across invocations, keyed by
// the config file's absolute path, and invalidated whenever its modification time or size
// changes — so an edit to `config.tel` is honoured by the very next `fume` command with no
// daemon restart. A file that fails to parse is treated (and cached) as absent rather than
// aborting the command; `fume` never requires a configuration file to exist.
object Workspace:
  private case class Cached(modified: Long, size: Long, config: Optional[Tel])

  private val cache: TrieMap[Text, Cached] = TrieMap()

  // The nearest `.fume/config.tel` at or above `directory`, or `Unset` if no ancestor has one.
  // The FILE is what is sought: a `.fume` directory without a `config.tel` does not end the
  // search, so an empty `.fume` (e.g. holding only future state like caches) is harmless.
  def locate(directory: Text): Optional[Path on Linux] =
    safely:
      def recur(dir: Path on Linux): Optional[Path on Linux] =
        val candidate = dir / Name[Linux](t".fume") / Name[Linux](t"config.tel")
        if candidate.existent() then candidate else dir.parent.let(recur(_))

      recur(directory.as[Path on Linux])

  // Reads and parses the file in two separately-scoped `safely` regions — one `Tactic` for the
  // filesystem read, another for the TEL parse — rather than one region with a union `Tactic`:
  // a single tactic would be captured by both the path-reader given and the TEL aggregator,
  // which separation checking rejects as overlapping hidden capabilities.
  private def parse(file: Path on Linux): Optional[Tel] =
    safely(file.read[Data]).let { data => safely(data.read[Tel]) }

  // The parsed configuration governing `directory`, freshly stat-checked on every call. One
  // `stat` yields both the modification time and the size, so the change check costs a single
  // filesystem operation per invocation.
  def config(directory: Text): Optional[Tel] =
    locate(directory).let: file =>
      safely(summon[FilesystemBackend on Linux].stat(file, true)).let: stat =>
        val key: Text = file.encode

        def reload(): Optional[Tel] =
          val parsed: Optional[Tel] = parse(file)
          cache(key) = Cached(stat.modified, stat.size, parsed)
          parsed

        cache.get(key) match
          case Some(cached) if cached.modified == stat.modified && cached.size == stat.size =>
            cached.config

          case _ =>
            reload()

  // A configuration source for the `Setting` cascade: keyed by the setting's canonical
  // camelCase name, translated to its kebab-case TEL keyword, and applying the three value
  // rules above.
  def configurator(directory: Text): Configurator =
    name =>
      config(directory).let: tel =>
        val matches: List[Tel] = tel.fields(name.uncamel.kebab).to[List]

        if matches.nil then Unset else
          val atoms = matches.map(_.primaryAtom).filter(_ != t"")
          if atoms.nil then t"true" else atoms.join(t":")
