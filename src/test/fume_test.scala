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

import java.io as ji
import java.nio.file as jnf

import soundness.*

object Tests extends Suite(m"Fume tests"):
  // A fresh project directory whose `.fume/config.tel` holds `content`, with a nested
  // `sub/dir` to invoke from, so the upward search is exercised. Rooted in a unique temporary
  // directory per call.
  private def project(content: Text): ji.File =
    val root = jnf.Files.createTempDirectory("fume-test").nn.toFile.nn
    val fumeDir = ji.File(root, ".fume").nn
    fumeDir.mkdirs()
    val nested = ji.File(root, "sub/dir").nn
    nested.mkdirs()
    jnf.Files.write(ji.File(fumeDir, "config.tel").nn.toPath, content.s.getBytes("UTF-8"))
    nested

  private def read(directory: ji.File, name: Text): Optional[Text] =
    Workspace.configurator(directory.getAbsolutePath.nn.tt).read(name)

  def run(): Unit =
    test(m"the version is set"):
      fumeVersion
    . assert(_ == t"0.1.0")

    test(m"a config file is located in an ancestor directory"):
      read(project(t"tel 1.0\n\nclasspath out/tests.jar\n"), t"classpath")
    . assert(_ == t"out/tests.jar")

    test(m"an absent keyword reads as Unset"):
      read(project(t"tel 1.0\n\nclasspath out/tests.jar\n"), t"failFast")
    . assert(_ == Unset)

    test(m"a directory with no config reads as Unset"):
      val empty = jnf.Files.createTempDirectory("fume-empty").nn.toFile.nn
      read(empty, t"classpath")
    . assert(_ == Unset)

    test(m"repeated classpath entries join with ':'"):
      read(project(t"tel 1.0\n\nclasspath out/a.jar\nclasspath out/b.jar\n"), t"classpath")
    . assert(_ == t"out/a.jar:out/b.jar")

    test(m"a bare keyword reads as true"):
      read(project(t"tel 1.0\n\nfail-fast\n"), t"failFast")
    . assert(_ == t"true")

    test(m"suites are discovered from a directory-form classpath entry"):
      val root = jnf.Files.createTempDirectory("fume-classes").nn.toFile.nn
      val services = ji.File(root, "META-INF/services").nn
      services.mkdirs()

      jnf.Files.write
        ( ji.File(services, "probably.Suite").nn.toPath,
          "# source: one.scala\nexample.Tests\n\n# source: two.scala\nother.Tests\n".getBytes("UTF-8") )

      Suites.discover(LocalClasspath(List(Classpath.Entry.Directory(root.getAbsolutePath.nn.tt))*))
    . assert(_ == List(t"example.Tests", t"other.Tests"))

    test(m"glob classpath entries expand, sorted, one segment at a time"):
      val root = jnf.Files.createTempDirectory("fume-glob").nn.toFile.nn
      ji.File(root, "b/test").nn.mkdirs()
      ji.File(root, "a/test").nn.mkdirs()
      ji.File(root, "a/other").nn.mkdirs()
      jnf.Files.write(ji.File(root, "b/test/out.jar").nn.toPath, "x".getBytes)
      jnf.Files.write(ji.File(root, "a/test/out.jar").nn.toPath, "x".getBytes)
      val base = root.getAbsolutePath.nn.tt

      Suites.expand(base, t"$base/*/test/out.jar").map(_.skip(base.length))
    . assert(_ == List(t"/a/test/out.jar", t"/b/test/out.jar"))

    test(m"glob classpath entries support ? and character ranges"):
      val root = jnf.Files.createTempDirectory("fume-glob2").nn.toFile.nn
      jnf.Files.write(ji.File(root, "m1.jar").nn.toPath, "x".getBytes)
      jnf.Files.write(ji.File(root, "m2.jar").nn.toPath, "x".getBytes)
      jnf.Files.write(ji.File(root, "n1.jar").nn.toPath, "x".getBytes)
      val base = root.getAbsolutePath.nn.tt

      Suites.expand(base, t"$base/m?.jar").map(_.skip(base.length))
        + Suites.expand(base, t"$base/[n]1.jar").map(_.skip(base.length))
    . assert(_ == List(t"/m1.jar", t"/m2.jar", t"/n1.jar"))

    test(m"a whole-segment ** spans directories"):
      val root = jnf.Files.createTempDirectory("fume-globstar").nn.toFile.nn
      ji.File(root, "x/deep/test").nn.mkdirs()
      ji.File(root, "y").nn.mkdirs()
      jnf.Files.write(ji.File(root, "x/deep/test/out.jar").nn.toPath, "x".getBytes)
      jnf.Files.write(ji.File(root, "y/out.jar").nn.toPath, "x".getBytes)
      val base = root.getAbsolutePath.nn.tt

      Suites.expand(base, t"$base/**/out.jar").map(_.skip(base.length))
    . assert(_ == List(t"/x/deep/test/out.jar", t"/y/out.jar"))

    test(m"an edited config file is reparsed"):
      val directory = project(t"tel 1.0\n\nclasspath out/old.jar\n")
      val first = read(directory, t"classpath")
      val root = directory.getParentFile.nn.getParentFile.nn
      val file = ji.File(ji.File(root, ".fume"), "config.tel")
      jnf.Files.write(file.toPath, "tel 1.0\n\nclasspath out/renewed.jar\n".getBytes("UTF-8"))
      (first, read(directory, t"classpath"))
    . assert(_ == (t"out/old.jar", t"out/renewed.jar"))
