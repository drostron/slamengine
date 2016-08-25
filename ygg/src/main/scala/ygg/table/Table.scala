package ygg.table

import scalaz._
import ygg._, common._, json._
import trans._

trait TableCompanion {
  type Table <: ygg.table.Table
  type LazyTable = Need[Table]

  def empty: Table
  def constString(v: scSet[String]): Table
  def constLong(v: scSet[Long]): Table
  def constDouble(v: scSet[Double]): Table
  def constDecimal(v: scSet[BigDecimal]): Table
  def constDate(v: scSet[DateTime]): Table
  def constBoolean(v: scSet[Boolean]): Table
  def constNull: Table
  def constEmptyObject: Table
  def constEmptyArray: Table
  def fromRValues(values: Stream[RValue], maxSliceSize: Option[Int]): Table

  def merge(grouping: GroupingSpec)(body: (RValue, GroupId => LazyTable) => LazyTable): LazyTable
  def align(sourceL: Table, alignL: TransSpec1, sourceR: Table, alignR: TransSpec1): Need[PairOf[Table]]
  def join(left: Table, right: Table, orderHint: Option[JoinOrder])(lspec: TransSpec1, rspec: TransSpec1, joinSpec: TransSpec2): M[JoinOrder -> Table]
  def cross(left: Table, right: Table, orderHint: Option[CrossOrder])(spec: TransSpec2): M[CrossOrder -> Table]
}

trait Table {
  type Table <: ygg.table.Table
  type LazyTable = Need[Table]

  def slices: StreamT[Need, Slice]

  /**
    * Return an indication of table size, if known
    */
  def size: TableSize

  /**
    * For each distinct path in the table, load all columns identified by the specified
    * jtype and concatenate the resulting slices into a new table.
    */
  def load(apiKey: APIKey, tpe: JType): LazyTable

  /**
    * Folds over the table to produce a single value (stored in a singleton table).
    */
  def reduce[A: Monoid](reducer: CReducer[A]): Need[A]

  /**
    * Removes all rows in the table for which definedness is satisfied
    * Remaps the indicies.
    */
  def compact(spec: TransSpec1, definedness: Definedness = AnyDefined): Table

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
  def force: LazyTable

  def paged(limit: Int): Table

  /**
    * Sorts the KV table by ascending or descending order of a transformation
    * applied to the rows.
    *
    * @param sortKey The transspec to use to obtain the values to sort on
    * @param sortOrder Whether to sort ascending or descending
    * @param unique If true, the same key values will sort into a single row, otherwise
    * we assign a unique row ID as part of the key so that multiple equal values are
    * preserved
    */
  def sort(sortKey: TransSpec1, sortOrder: DesiredSortOrder, unique: Boolean): LazyTable

  def sort(sortKey: TransSpec1, sortOrder: DesiredSortOrder): LazyTable = sort(sortKey, sortOrder, unique = false)
  def sort(sortKey: TransSpec1): LazyTable                              = sort(sortKey, SortAscending)

  def distinct(spec: TransSpec1): Table
  def concat(t2: Table): Table
  def zip(t2: Table): LazyTable
  def toArray[A](implicit tpe: CValueType[A]): Table

  /**
    * Sorts the KV table by ascending or descending order based on a seq of transformations
    * applied to the rows.
    *
    * @param groupKeys The transspecs to use to obtain the values to sort on
    * @param valueSpec The transspec to use to obtain the non-sorting values
    * @param sortOrder Whether to sort ascending or descending
    * @param unique If true, the same key values will sort into a single row, otherwise
    * we assign a unique row ID as part of the key so that multiple equal values are
    * preserved
    */
  def groupByN(groupKeys: Seq[TransSpec1], valueSpec: TransSpec1, sortOrder: DesiredSortOrder, unique: Boolean): Need[Seq[Table]]
  def partitionMerge(partitionBy: TransSpec1)(f: Table => LazyTable): LazyTable
  def takeRange(startIndex: Long, numberToTake: Long): Table
  def canonicalize(length: Int): Table
  def schemas: Need[Set[JType]]
  def renderJson(prefix: String = "", delimiter: String = "\n", suffix: String = ""): StreamT[Need, CharBuffer]

  // for debugging only!!
  def toJson: Need[Stream[JValue]]
}
