package filodb.core.metadata

import org.scalatest.{FunSpec, Matchers}

import filodb.core._
import filodb.core.query.ColumnInfo

class DatasetSpec extends FunSpec with Matchers {
  import Column.ColumnType._
  import Dataset._
  import NamesTestData._

  describe("Dataset validation") {
    it("should return NotNameColonType if column specifiers not name:type format") {
      val resp1 = Dataset.make("dataset", Seq("part:string"), dataColSpecs :+ "column2", Seq("age"))
      resp1.isBad shouldEqual true
      resp1.swap.get shouldEqual ColumnErrors(Seq(NotNameColonType("column2")))

      intercept[BadSchemaError] {
        Dataset("dataset", Seq("part:string"), dataColSpecs :+ "column2:a:b", "age")
      }
    }

    it("should return BadColumnParams if name:type:params portion not valid key=value pairs") {
      val resp1 = Dataset.make("dataset", Seq("part:string"), dataColSpecs :+ "column2:a:b", Seq("age"))
      resp1.isBad shouldEqual true
      resp1.swap.get shouldBe a[ColumnErrors]
      val errors = resp1.swap.get.asInstanceOf[ColumnErrors].errs
      errors should have length 1
      errors.head shouldBe a[BadColumnParams]
    }

    it("should return BadColumnParams if required param config not specified") {
      val resp1 = Dataset.make("dataset", Seq("part:string"), dataColSpecs :+ "h:hist:foo=bar", Seq("age"))
      resp1.isBad shouldEqual true
      resp1.swap.get shouldBe a[ColumnErrors]
      val errors = resp1.swap.get.asInstanceOf[ColumnErrors].errs
      errors should have length 1
      errors.head shouldBe a[BadColumnParams]

      val resp2 = Dataset.make("dataset", Seq("part:string"), dataColSpecs :+ "h:hist:counter=bar", Seq("age"))
      resp2.isBad shouldEqual true
      resp2.swap.get shouldBe a[ColumnErrors]
      val errors2 = resp2.swap.get.asInstanceOf[ColumnErrors].errs
      errors2 should have length 1
      errors2.head shouldBe a[BadColumnParams]
    }

    it("should return BadColumnName if illegal chars in column name") {
      val resp1 = Dataset.make("dataset", Seq("part:string"), Seq("col, umn1:string"), Seq("age"))
      resp1.isBad shouldEqual true
      val errors = resp1.swap.get match {
        case ColumnErrors(errs) => errs
        case x => throw new RuntimeException(s"Did not expect $x")
      }
      errors should have length (1)
      errors.head shouldBe a[BadColumnName]
    }

    it("should return BadColumnType if unsupported type specified in column spec") {
      val resp1 = Dataset.make("dataset", Seq("part:linkedlist"), dataColSpecs, Seq("age"))
      resp1.isBad shouldEqual true
      val errors = resp1.swap.get match {
        case ColumnErrors(errs) => errs
        case x => throw new RuntimeException(s"Did not expect $x")
      }
      errors should have length (1)
      errors.head shouldEqual BadColumnType("linkedlist")
    }

    it("should return multiple column spec errors") {
      val resp1 = Dataset.make("dataset", Seq("part:string"),
                               Seq("first:str", "age:long", "la(st):int"), Seq("age"))
      resp1.isBad shouldEqual true
      val errors = resp1.swap.get match {
        case ColumnErrors(errs) => errs
        case x => throw new RuntimeException(s"Did not expect $x")
      }
      errors should have length (2)
      errors.head shouldEqual BadColumnType("str")
    }

    it("should return UnknownRowKeyColumn if row key column(s) not in data columns") {
      val resp1 = Dataset.make("dataset", Seq("part:string"), dataColSpecs, Seq("column2"))
      resp1.isBad shouldEqual true
      resp1.swap.get shouldEqual UnknownRowKeyColumn("column2")

      val resp2 = Dataset.make("dataset", Seq("part:string"), dataColSpecs, Seq("age", "column9"))
      resp2.isBad shouldEqual true
      resp2.swap.get shouldEqual UnknownRowKeyColumn("column9")
    }

    it("should allow MapColumns only in last position of partition key") {
      val mapCol = "tags:map"

      // OK: only partition column is map
      val ds1 = Dataset("dataset", Seq(mapCol), dataColSpecs, "age")
      ds1.partitionColumns.map(_.name) should equal (Seq("tags"))

      // OK: last partition column is map
      val ds2 = Dataset("dataset", Seq("first:string", mapCol), dataColSpecs drop 1, "age")
      ds2.partitionColumns.map(_.name) should equal (Seq("first", "tags"))

      // Not OK: first partition column is map
      val resp3 = Dataset.make("dataset", Seq(mapCol, "first:string"), dataColSpecs drop 1, Seq("age"))
      resp3.isBad shouldEqual true
      resp3.swap.get shouldBe an[IllegalMapColumn]

      // Not OK: map in data columns, not partition column
      intercept[BadSchemaError] {
        Dataset("dataset", Seq("seg:int"), dataColSpecs :+ mapCol, Seq("age")) }
    }

    it("should return NoTimestampRowKey if non timestamp used for row key") {
      val ds1 = Dataset.make("dataset", Seq("part:string"), dataColSpecs, Seq("first"))
      ds1.isBad shouldEqual true
      ds1.swap.get shouldBe a[NoTimestampRowKey]
    }

    it("should return a valid Dataset when a good specification passed") {
      val ds = Dataset("dataset", Seq("part:string"), dataColSpecs, "age")
      ds.rowKeyIDs shouldEqual Seq(2)
      ds.dataColumns should have length (3)
      ds.dataColumns.map(_.id) shouldEqual Seq(0, 1, 2)
      ds.dataColumns.map(_.columnType) shouldEqual Seq(StringColumn, StringColumn, LongColumn)
      ds.partitionColumns should have length (1)
      ds.partitionColumns.map(_.columnType) shouldEqual Seq(StringColumn)
      ds.partitionColumns.map(_.id) shouldEqual Seq(PartColStartIndex)
      Dataset.isPartitionID(ds.partitionColumns.head.id) shouldEqual true
      ds.timestampColumn.name shouldEqual "age"
      ds.rowKeyRouting shouldEqual Array(2)
    }

    it("should return valid Dataset when multiple row key columns specified") {
      val ds = Dataset("dataset", Seq("part:string"), dataColSpecs, Seq("age", "first"))
      ds.rowKeyIDs shouldEqual Seq(2, 0)
      ds.dataColumns should have length (3)
      ds.dataColumns.map(_.id) shouldEqual Seq(0, 1, 2)
      ds.dataColumns.map(_.columnType) shouldEqual Seq(StringColumn, StringColumn, LongColumn)
      ds.partitionColumns should have length (1)
      ds.partitionColumns.map(_.columnType) shouldEqual Seq(StringColumn)
      ds.timestampColumn.name shouldEqual "age"
      ds.rowKeyRouting shouldEqual Array(2, 0)
    }

    it("should return IDs for column names or seq of missing names") {
      val ds = Dataset("dataset", Seq("part:string"), dataColSpecs, "age")
      ds.colIDs("first", "age").get shouldEqual Seq(0, 2)

      ds.colIDs("part").get shouldEqual Seq(Dataset.PartColStartIndex)

      val resp1 = ds.colIDs("last", "unknown")
      resp1.isBad shouldEqual true
      resp1.swap.get shouldEqual Seq("unknown")
    }

    it("should return ColumnInfos for colIDs") {
      val ds = Dataset("dataset", Seq("part:string"), dataColSpecs, "age")
      val infos = ds.infosFromIDs(Seq(0, 2))
      infos shouldEqual Seq(ColumnInfo("first", StringColumn), ColumnInfo("age", LongColumn))

      val infos2 = ds.infosFromIDs(Seq(PartColStartIndex, 1))
      infos2 shouldEqual Seq(ColumnInfo("part", StringColumn), ColumnInfo("last", StringColumn))
    }

    it("should compute nonMetricShardColumns correctly") {
      val options = DatasetOptions.DefaultOptions.copy(shardKeyColumns = Seq("job", "__name__"))
      options.nonMetricShardColumns shouldEqual Seq("job")
      options.nonMetricShardKeyBytes.size shouldEqual 1
    }
  }

  describe("Dataset serialization") {
    it("should serialize and deserialize") {
      val ds = Dataset("dataset", Seq("part:string"), dataColSpecs, Seq("age"), Seq("dMin(1)"))
                 .copy(options = DatasetOptions.DefaultOptions.copy(
                   copyTags = Map("exporter" -> "_ns")))
      Dataset.fromCompactString(ds.asCompactString) shouldEqual ds

      val ds2 = ds.copy(options = DatasetOptions.DefaultOptions.copy(shardKeyColumns = Seq("metric")))
      Dataset.fromCompactString(ds2.asCompactString) shouldEqual ds2
    }
  }

  describe("DatasetOptions serialization") {
    it("should serialize options successfully") {
      val options = DatasetOptions.DefaultOptions.copy(shardKeyColumns = Seq("job", "__name__"))
      DatasetOptions.fromString(options.toString) should equal (options)
    }
  }
}