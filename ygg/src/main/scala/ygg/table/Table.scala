/*
 * Copyright 2014–2016 SlamData Inc.
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

package ygg.table

import ygg._, common._, json._
import trans._
import quasar._
import quasar.ejson.EJson
import scalaz.{ Source => _, _ }
import Scalaz.{ ToIdOps => _, _ }
import JDBM.IndexMap

private object addGlobalIdScanner extends Scanner {
  type A = Long
  val init = 0l
  def scan(a: Long, cols: ColumnMap, range: Range): A -> ColumnMap = {
    val globalIdColumn = new RangeColumn(range) with LongColumn { def apply(row: Int) = a + row }
    (a + range.end + 1, cols + (ColumnRef(CPath(CPathIndex(1)), CLong) -> globalIdColumn))
  }
}

class TableSelector[A <: Table](val table: A { type Table = A }) {
  def >>(): Unit = table.toVector foreach println

  def filter(p: TransSpec[Source.type]) = table transform (root filter p)
}
object Table extends BlockTableModule {
  implicit val codec = DataCodec.Precise

  def apply(json: String): BaseTable = fromJson(JParser.parseManyFromString(json).fold(throw _, x => x))
  def apply(file: jFile): BaseTable  = apply(file.slurpString)

  def toJson(dataset: Table): Need[Stream[JValue]] = dataset.toJson.map(_.toStream)
  def toJsonSeq(table: Table): Seq[JValue]         = toJson(table).copoint
  def fromData(data: Vector[Data]): BaseTable      = fromJson(data map dataToJValue)
  def fromFile(file: jFile): BaseTable             = fromJson((JParser parseManyFromFile file).orThrow)
  def fromString(json: String): BaseTable          = fromJson(Seq(JParser parseUnsafe json))
  def fromJValues(json: JValue*): BaseTable        = fromJson(json.toVector)
}

trait TableConstructors[T <: ygg.table.Table] {
  type JsonRep
  type Seq[A]

  def maxSliceSize: Int

  def empty: T
  def fromData(data: Data): T
  def fromEJson[A](json: EJson[A])(f: A => JsonRep): T
  def fromFile(file: jFile): T
  def fromJson(json: String): T
  def fromJValues(data: Seq[JsonRep]): T
  def fromRValues(data: Seq[RValue]): T
  def fromSlice(data: Slice): T
  def fromSlices(data: Seq[Slice]): T
}

object TableCompanion {
  def apply[A <: Table](implicit z: TableCompanion[A]): TableCompanion[A] = z
}

trait BlockTableCompanion[T <: ygg.table.Table] extends TableCompanion[T] {
  lazy val sortMergeEngine = new MergeEngine

  def addGlobalId(spec: TransSpec1): TransSpec1                                        = Scan(WrapArray(spec), addGlobalIdScanner)
  def align(sourceL: T, alignL: TransSpec1, sourceR: T, alignR: TransSpec1): PairOf[T] = AlignTable[T](sourceL, alignL, sourceR, alignR)(this)

  def loadTable(mergeEngine: MergeEngine, indices: IndexMap, sortOrder: DesiredSortOrder): T
  def writeAlignedSlices(kslice: Slice, vslice: Slice, jdbmState: JDBMState, indexNamePrefix: String, sortOrder: DesiredSortOrder): Need[JDBMState]
  def reduceSlices(slices: NeedSlices): NeedSlices
}

trait TableCompanion[T <: ygg.table.Table] {
  type M[X]  = T#M[X]
  type Table = T

  def empty: T
  def apply(slices: NeedSlices, size: TableSize): T
  // TODO assert that this table only has one row
  def newInternalTable(slice: Slice): T

  def constString(v: scSet[String]): T
  def constLong(v: scSet[Long]): T
  def constDouble(v: scSet[Double]): T
  def constDecimal(v: scSet[BigDecimal]): T
  def constDate(v: scSet[DateTime]): T
  def constBoolean(v: scSet[Boolean]): T
  def constNull: T
  def constEmptyObject: T
  def constEmptyArray: T
  def fromRValues(values: Stream[RValue], maxSliceSize: Option[Int]): T

  def merge(grouping: GroupingSpec[T])(body: (RValue, GroupId => Need[T]) => Need[T]): Need[T]

  def cogroup(self: Table, leftKey: TransSpec1, rightKey: TransSpec1, that: Table)(leftResultTrans: TransSpec1, rightResultTrans: TransSpec1, bothResultTrans: TransSpec2): Table =
    CogroupTable[T](self, leftKey, rightKey, that)(leftResultTrans, rightResultTrans, bothResultTrans)(this)

  /**
    * Sorts the KV table by ascending or descending order of a transformation
    * applied to the rows.
    *
    * @param key The transspec to use to obtain the values to sort on
    * @param order Whether to sort ascending or descending
    */
  def sort[F[_]: Monad](table: T, key: TransSpec1, order: DesiredSortOrder): F[T]       = sortCommon[F](table, key, order, unique = false)
  def sortUnique[F[_]: Monad](table: T, key: TransSpec1, order: DesiredSortOrder): F[T] = sortCommon[F](table, key, order, unique = true)

  private def sortCommon[F[_]: Monad](table: T, key: TransSpec1, order: DesiredSortOrder, unique: Boolean): F[T] = table match {
    case _: SingletonTable => table.point[F]
    case _: InternalTable  => sortCommon[F](fixTable[T](table.toExternalTable), key, order, unique)
    case _: ExternalTable  => groupByN[F](table, Seq(key), root, order, unique) map (_.headOption getOrElse empty)
  }

  /**
    * Sorts the KV table by ascending or descending order based on a seq of transformations
    * applied to the rows.
    *
    * @param keys The transspecs to use to obtain the values to sort on
    * @param values The transspec to use to obtain the non-sorting values
    * @param order Whether to sort ascending or descending
    * @param unique If true, the same key values will sort into a single row, otherwise
    * we assign a unique row ID as part of the key so that multiple equal values are
    * preserved
    */
  def groupByN[F[_]: Monad](table: T, keys: Seq[TransSpec1], values: TransSpec1, order: DesiredSortOrder, unique: Boolean): F[Seq[T]] = table match {
    case _: SingletonTable => table.transform(values) |> (xform => Seq.fill(keys.size)(fixTable[T](xform)).point[F])
    case _: InternalTable  => groupByN[F](fixTable[T](table.toExternalTable), keys, values, order, unique)
    case t: ExternalTable  => ().point[F] map (_ => fixTables(t.groupExternalByN(keys, values, order, unique).value))
  }

  /**
    * Converts a table to an internal table, if possible. If the table is
    * already an `InternalTable` or a `SingletonTable`, then the conversion
    * will always succeed. If the table is an `ExternalTable`, then if it has
    * less than `limit` rows, it will be converted to an `InternalTable`,
    * otherwise it will stay an `ExternalTable`.
    */
  def toInternalTable(table: T, limit: Int): T#ExternalTable \/ T#InternalTable = table match {
    case x: SingletonTable       => x.slices.toStream.map(xs => \/-(fixTable[T#InternalTable](newInternalTable(Slice concat xs takeRange (0, 1))))).value

    // \/-(fixTable[T#InternalTable](x.slices.toStream.map(xs => \/-(new InternalTable(Slice concat xs takeRange (0, 1)))).value
    case x: InternalTable        => \/-(fixTable[T#InternalTable](x))
    case x: ExternalTable with T => x externalToInternal limit
  }

  def constSingletonTable(singleType: CType, column: Column): T
  def constSliceTable[A: CValueType](vs: Array[A], mkColumn: Array[A] => Column): T
  def fromJson(data: Seq[JValue]): T
  def load(table: T, tpe: JType): Need[T]
}

trait TemporaryTableStrut extends Table {
  /** XXX FIXME */
  def toExternalTable(): ExternalTable = ???
  def takeRange(startIndex: Long, numberToTake: Long): Table = takeRangeDefaultImpl(startIndex, numberToTake)
  def takeRangeDefaultImpl(startIndex: Long, numberToTake: Long): Table
}

trait InternalTable extends Table {
  def slice: Slice
}
trait ExternalTable extends Table {
  def groupExternalByN(groupKeys: Seq[TransSpec1], valueSpec: TransSpec1, sortOrder: DesiredSortOrder, unique: Boolean): M[Seq[Table]]
  def externalToInternal(limit: Int): ExternalTable \/ InternalTable
}
trait SingletonTable extends Table {
  def slice: Need[Slice]
}

trait Table {
  type Table <: ygg.table.Table
  type InternalTable <: Table with ygg.table.InternalTable
  type ExternalTable <: Table with ygg.table.ExternalTable
  type SingletonTable <: Table with ygg.table.SingletonTable

  type M[X]       = Need[X]
  type NeedSlices = StreamT[M, Slice]

  /**
    * Return an indication of table size, if known
    */
  def size: TableSize

  /**
    * Folds over the table to produce a single value (stored in a singleton table).
    */
  def reduce[A: Monoid](reducer: CReducer[A]): M[A]

  /**
    * Removes all rows in the table for which definedness is satisfied
    * Remaps the indicies.
    */
  def compact(spec: TransSpec1, definedness: Definedness): Table

  /**
    * Performs a one-pass transformation of the keys and values in the table.
    * If the key transform is not identity, the resulting table will have
    * unknown sort order.
    */
  def transform(spec: TransSpec1): Table

  /**
    * Cogroups this table with another table, using equality on the specified
    * transformation on rows of the table.
    */
  def cogroup(leftKey: TransSpec1, rightKey: TransSpec1, that: Table)(left: TransSpec1, right: TransSpec1, both: TransSpec2): Table

  /**
    * Performs a full cartesian cross on this table with the specified table,
    * applying the specified transformation to merge the two tables into
    * a single table.
    */
  def cross(that: Table)(spec: TransSpec2): Table

  /**
    * Force the table to a backing store, and provice a restartable table
    * over the results.
    */
  def force: M[Table]

  /**
    * Converts a table to an internal table, if possible. If the table is
    * already an `InternalTable` or a `SingletonTable`, then the conversion
    * will always succeed. If the table is an `ExternalTable`, then if it has
    * less than `limit` rows, it will be converted to an `InternalTable`,
    * otherwise it will stay an `ExternalTable`.
    */
  def toExternalTable(): ExternalTable

  /**
    * For each distinct path in the table, load all columns identified by the specified
    * jtype and concatenate the resulting slices into a new table.
    */
  def load(tpe: JType): M[Table]

  def canonicalize(length: Int): Table
  def canonicalize(minLength: Int, maxLength: Int): Table
  def columns: ColumnMap
  def companion: TableCompanion[Table]
  def concat(t2: Table): Table
  def distinct(key: TransSpec1): Table
  def mapWithSameSize(f: EndoA[NeedSlices]): Table
  def normalize: Table
  def paged(limit: Int): Table
  def partitionMerge(partitionBy: TransSpec1)(f: Table => M[Table]): M[Table]
  def sample(sampleSize: Int, specs: Seq[TransSpec1]): M[Seq[Table]]
  def schemas: M[Set[JType]]
  def slices: NeedSlices
  def takeRange(startIndex: Long, numberToTake: Long): Table
  def toArray[A](implicit tpe: CValueType[A]): Table
  def toData: Data
  def toJValues: Stream[JValue]
  def toJson: M[Stream[JValue]]
  def zip(t2: Table): M[Table]

  def slicesStream: Stream[Slice]
  def toVector: Vector[JValue]
  def toDataStream: Stream[Data]
  def fields = slicesStream.flatMap(_.toJsonElements).toVector
}
