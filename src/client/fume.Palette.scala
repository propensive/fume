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

import soundness.*

import luminosity.darkBrightness
import themes.solarizedTheme

// Fume's colour palette: the solarized-derived scheme probably's reporting used, now owned by
// the consumer of the event stream. A singleton, not a `given`: fume renders with exactly one
// palette, so call sites name `Palette.pass` directly rather than threading a capability-free
// context parameter through every renderer.
object Palette extends iridescence.Palette:
  type Form = Srgb

  private val theme: Theme = summon[Theme]

  val background:  Color in Srgb = theme.background.to[Srgb]
  val foreground:  Color in Srgb = theme.foreground.to[Srgb]

  private val yellow: Color in Srgb = theme.spectrum.yellow.to[Srgb]
  private val red:    Color in Srgb = theme.spectrum.red.to[Srgb]
  private val blue:   Color in Srgb = theme.spectrum.blue.to[Srgb]

  val warning:     Color in Srgb = yellow
  val critical:    Color in Srgb = theme.spectrum.magenta.to[Srgb]
  val benchmark:   Color in Srgb = theme.spectrum.cyan.to[Srgb]
  val mixed:       Color in Srgb = blue
  val informative: Color in Srgb = blue
  val cold:        Color in Srgb = mix(yellow, red, 0.2)
  val warm:        Color in Srgb = mix(yellow, red, 0.5)
  val hot:         Color in Srgb = mix(yellow, red, 0.8)
  val accented:    Color in Srgb = theme.spectrum.cyan.to[Srgb]
  val highlight:   Color in Srgb = accent(yellow)
  val pass:        Color in Srgb = theme.spectrum.green.to[Srgb]
  val fail:        Color in Srgb = red
  val aspirePass:  Color in Srgb = mix(theme.spectrum.green.to[Srgb], theme.spectrum.cyan.to[Srgb], 0.5)
  val aspireFail:  Color in Srgb = subdue(yellow, 0.5)
  val detail:      Color in Srgb = blue
  val subdued:     Color in Srgb = subdue(theme.foreground.to[Srgb], 0.5)
  val unaccented:  Color in Srgb = subdued
  val positive:    Color in Srgb = pass
  val negative:    Color in Srgb = fail
