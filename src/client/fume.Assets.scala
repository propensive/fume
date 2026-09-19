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
┃    Fume, version 0.4.0.                                                                          ┃
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

import soundness.*

import charDecoders.utf8Decoder
import charEncoders.utf8Encoder
import denominative.dysasymptotics.linearAccess
import logging.silentLogging
import textSanitizers.skipSanitizer

// The static files the dashboard serves beyond pyrocosm's own: the invokers' icons, read from
// the client jar's `fume/` resources (the build puts `res/` on the classpath). Pyrocosm's web
// frontend answers only its own paths and hands every other request to a fallback, which is
// what `serve` is.
object Assets:
  private val mutex: Mutex = Mutex()

  // The icons read so far, by basename.
  @scala.caps.unsafe.untrackedCaptures
  private var icons: Map[Text, Text] = Map()

  // The path an invoker's icon is served at.
  def location(icon: Text): Text = t"/fume/$icon.svg"

  // A resource as text, through the thread-context classloader: under Burdock, fume's own
  // classes live in the launcher's sibling loader, which the system loader cannot see (see the
  // note on `ui.Classpath`).
  private def read(resource: Text): Optional[Text] =
    given Classloader = Classloader.threadContext
    safely(resource.as[Path on Classpath]).let { path => safely(path.read[Text]) }

  // An icon by the path it is served at. Only a path naming an invoker's icon is answered, so
  // no other resource can be reached through the dashboard.
  def asset(path: Text): Optional[Text] =
    Invoker.all.seek(_.icon.let(location(_)) == path).let(_.icon).let: icon =>
      mutex:
        icons(icon).or:
          read(t"/fume/$icon.svg").let: svg =>
            icons = icons.define(icon, svg)
            svg

  // The fallback for pyrocosm's `WebFrontend`: an icon's SVG, or `Unset` for it to answer 404.
  // A method, not a function value, so that `Assets` holds no capability.
  def serve(request: Http.Request): Optional[Http.Response] =
    val path: Text = request.target.cut(t"?").at(Prim).or(t"/")

    asset(path).let: svg =>
      Http.Ok(List(Http.Header(t"content-type", t"image/svg+xml")), Http.Body.Fixed(svg.in[Data]))
