package org.ekstep.analytics.dashboard.exhaust

import org.apache.spark.SparkContext
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import org.ekstep.analytics.dashboard.DashboardUtil._
import org.ekstep.analytics.dashboard.{AbsDashboardModel, DashboardConfig}
import org.ekstep.analytics.framework._

/**
 * Model for processing dashboard data
 */
object DataExhaustModel extends AbsDashboardModel {

  implicit val className: String = "org.ekstep.analytics.dashboard.exhaust.DataExhaustModel"
  override def name() = "DataExhaustModel"

  /**
   * Master method, does all the work, fetching, processing and dispatching
   *
   * @param timestamp unique timestamp from the start of the processing
   */
  def processData(timestamp: Long)(implicit spark: SparkSession, sc: SparkContext, fc: FrameworkContext, conf: DashboardConfig): Unit = {
    val enrolmentDF = cassandraTableAsDataFrame(conf.cassandraCourseKeyspace, conf.cassandraUserEnrolmentsTable)
    cache.write(enrolmentDF, "enrolment")

    val batchDF = cassandraTableAsDataFrame(conf.cassandraCourseKeyspace, conf.cassandraCourseBatchTable)
    cache.write(batchDF, "batch")

    val userAssessmentDF = cassandraTableAsDataFrame(conf.cassandraUserKeyspace, conf.cassandraUserAssessmentTable)
    cache.write(userAssessmentDF, "userAssessment")

    val hierarchyDF = cassandraTableAsDataFrame(conf.cassandraHierarchyStoreKeyspace, conf.cassandraContentHierarchyTable)
    cache.write(hierarchyDF, "hierarchy")

    val ratingSummaryDF = cassandraTableAsDataFrame(conf.cassandraUserKeyspace, conf.cassandraRatingSummaryTable)
    cache.write(ratingSummaryDF, "ratingSummary")

    val acbpDF = cassandraTableAsDataFrame(conf.cassandraUserKeyspace, conf.cassandraAcbpTable)
    cache.write(acbpDF, "acbp")

    val ratingDF = cassandraTableAsDataFrame(conf.cassandraUserKeyspace, conf.cassandraRatingsTable)
    cache.write(ratingDF, "rating")

    val roleDF = cassandraTableAsDataFrame(conf.cassandraUserKeyspace, conf.cassandraUserRolesTable)
    cache.write(roleDF, "role")

    // ES content
    val primaryCategories = Seq("Course", "Program", "Blended Program", "CuratedCollections", "Standalone Assessment", "Curated Program")
    val shouldClause = primaryCategories.map(pc => s"""{"match":{"primaryCategory.raw":"${pc}"}}""").mkString(",")
    val fields = Seq("identifier", "name", "primaryCategory", "status", "reviewStatus", "channel", "duration", "leafNodesCount", "lastPublishedOn", "lastStatusChangedOn", "createdFor", "competencies_v5", "programDirectorName")
    val arrayFields = Seq("createdFor")
    val fieldsClause = fields.map(f => s""""${f}"""").mkString(",")
    val query = s"""{"_source":[${fieldsClause}],"query":{"bool":{"should":[${shouldClause}]}}}"""
    val esContentDF = elasticSearchDataFrame(conf.sparkElasticsearchConnectionHost, "compositesearch", query, fields, arrayFields)
    cache.write(esContentDF, "esContent")

    val orgDF = cassandraTableAsDataFrame(conf.cassandraUserKeyspace, conf.cassandraOrgTable)
    cache.write(orgDF, "org")

    // org hierarchy
    val appPostgresUrl = s"jdbc:postgresql://${conf.appPostgresHost}/${conf.appPostgresSchema}"
    val orgPostgresDF = postgresTableAsDataFrame(appPostgresUrl, conf.appOrgHierarchyTable, conf.appPostgresUsername, conf.appPostgresCredential)
    val orgCassandraDF = orgDF.select(
      col("id").alias("sborgid"),
      col("organisationtype").alias("orgType"),
      col("orgname").alias("cassOrgName"),
      col("createddate").alias("orgCreatedDate")
    )
    val orgDfWithOrgType = orgCassandraDF.join(orgPostgresDF, Seq("sborgid"), "left")
    val orgHierarchyDF = orgDfWithOrgType.select(
      col("sborgid").alias("mdo_id"),
      col("cassOrgName").alias("mdo_name"),
      col("l1orgname").alias("ministry"),
      col("l2orgname").alias("department"),
      to_date(to_timestamp(col("orgCreatedDate"))).alias("mdo_created_on"),
      col("orgType")
    )
      .withColumn("is_content_provider",
        when(col("orgType").cast("int") === 128 || col("orgType").cast("int") === 128, lit("Y")).otherwise(lit("N")))
      .withColumn("organization", when(col("ministry").isNotNull && col("department").isNotNull, col("mdo_name")).otherwise(null))
      .withColumn("data_last_generated_on", date_format(current_timestamp(), "yyyy-MM-dd HH:mm:ss a"))
      .distinct()
      .drop("orgType")
      .dropDuplicates(Seq("mdo_id"))
    cache.write(orgHierarchyDF, "orgHierarchy")

    val userDF = cassandraTableAsDataFrame(conf.cassandraUserKeyspace, conf.cassandraUserTable)
    cache.write(userDF, "user")
  }

}