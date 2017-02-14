/*
 * Copyright 2014–2017 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.physical.marklogic.qscript

import quasar.contrib.pathy.{AFile, UriPathCodec}
import quasar.physical.marklogic.xquery._
import quasar.physical.marklogic.xquery.syntax._
import quasar.qscript._

import matryoshka._
import scalaz._, Scalaz._

private[qscript] final class ReadFilePlanner[F[_]: Applicative, FMT: SearchOptions]
  extends Planner[F, FMT, Const[Read[AFile], ?]] {

  import axes.child

  val plan: AlgebraM[F, Const[Read[AFile], ?], XQuery] = {
    case Const(Read(file)) =>
      (cts.search(
        expr    = fn.doc(),
        query   = cts.documentQuery(UriPathCodec.printPath(file).xs),
        options = SearchOptions[FMT].searchOptions
      ) `/` child.node()).point[F]
  }
}
