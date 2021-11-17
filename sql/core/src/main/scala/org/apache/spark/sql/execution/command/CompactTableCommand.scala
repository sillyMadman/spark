/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.execution.command

import org.apache.spark.sql.{Row, SaveMode, SparkSession}
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.expressions.{Attribute, AttributeReference}
import org.apache.spark.sql.types.StringType





case class CompactTableCommand(table: TableIdentifier,
                               filesNum: Option[String],
                               partitionSpec: Option[String])
  extends LeafRunnableCommand {

  private val defaultSize = 128 * 1024 * 1024

  override def output: Seq[Attribute] = Seq(
    AttributeReference("COMPACT_TABLE", StringType, nullable = false )()
  )


  override def run(sparkSession: SparkSession): Seq[Row] = {
    sparkSession.catalog.setCurrentDatabase(table.database.getOrElse("default"))

    val tempTableName = "`" + table.identifier + "_" + System.currentTimeMillis() + "`"

    val originDataFrame = sparkSession.table(table.identifier)
    val partitions = filesNum match {
      case Some(files) => files.toInt
      case None => (sparkSession.sessionState
        .executePlan(originDataFrame.queryExecution.logical)
        .optimizedPlan.stats.sizeInBytes / defaultSize).toInt + 1
    }

    if (partitionSpec.nonEmpty) {
      sparkSession.conf.set("spark.sql.sources.partitionOverwriteMode", "dynamic")

      val conditionExpr = partitionSpec.get.trim.stripPrefix("partition(").dropRight(1)
        .replace(",", "AND")

      originDataFrame.filter(conditionExpr).repartition(partitions)
        .write
        .mode(SaveMode.Overwrite)
        .saveAsTable(tempTableName)

      sparkSession.table(tempTableName)
        .write
        .mode(SaveMode.Overwrite)
        .saveAsTable(table.identifier)
    } else {
      originDataFrame.repartition(partitions)
        .write
        .mode(SaveMode.Overwrite)
        .saveAsTable(tempTableName)

      sparkSession.table(tempTableName)
        .write
        .mode(SaveMode.Overwrite)
        .saveAsTable(table.identifier)
    }

    Seq(Row(s"compact table ${table.identifier} finished."))


  }
}
