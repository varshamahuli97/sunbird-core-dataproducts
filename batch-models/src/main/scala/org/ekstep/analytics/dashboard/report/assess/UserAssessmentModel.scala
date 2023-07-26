package org.ekstep.analytics.dashboard.report.assess

import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import org.ekstep.analytics.dashboard.{DashboardConfig, DummyInput, DummyOutput}
import org.ekstep.analytics.dashboard.DashboardUtil._
import org.ekstep.analytics.dashboard.DataUtil._
import org.ekstep.analytics.framework.{IBatchModelTemplate, _}

import java.io.Serializable


object UserAssessmentModel extends IBatchModelTemplate[String, DummyInput, DummyOutput, DummyOutput] with Serializable {

  implicit val className: String = "org.ekstep.analytics.dashboard.report.assess.UserAssessmentModel"
  override def name() = "UserAssessmentModel"

  override def preProcess(data: RDD[String], config: Map[String, AnyRef])(implicit sc: SparkContext, fc: FrameworkContext): RDD[DummyInput] = {
    // we want this call to happen only once, so that timestamp is consistent for all data points
    val executionTime = System.currentTimeMillis()
    sc.parallelize(Seq(DummyInput(executionTime)))
  }

  override def algorithm(data: RDD[DummyInput], config: Map[String, AnyRef])(implicit sc: SparkContext, fc: FrameworkContext): RDD[DummyOutput] = {
    val timestamp = data.first().timestamp  // extract timestamp from input
    implicit val spark: SparkSession = SparkSession.builder.config(sc.getConf).getOrCreate()
    processUserAssessmentData(timestamp, config)
    sc.parallelize(Seq())  // return empty rdd
  }

  override def postProcess(data: RDD[DummyOutput], config: Map[String, AnyRef])(implicit sc: SparkContext, fc: FrameworkContext): RDD[DummyOutput] = {
    sc.parallelize(Seq())  // return empty rdd
  }


  /**
   * Master method, does all the work, fetching, processing and dispatching
   *
   * @param timestamp unique timestamp from the start of the processing
   * @param config model config, should be defined at sunbird-data-pipeline:ansible/roles/data-products-deploy/templates/model-config.j2
   */
  def processUserAssessmentData(timestamp: Long, config: Map[String, AnyRef])(implicit spark: SparkSession, sc: SparkContext, fc: FrameworkContext): Unit = {
    // parse model config
    println(config)
    implicit val conf: DashboardConfig = parseConfig(config)
    if (conf.debug == "true") debug = true // set debug to true if explicitly specified in the config
    if (conf.validation == "true") validation = true // set validation to true if explicitly specified in the config

    // obtain user org data
    var (orgDF, userDF, userOrgDF) = getOrgUserDataFrames()

    // get course details, with rating info
    val (hierarchyDF, allCourseProgramDetailsWithCompDF, allCourseProgramDetailsDF,
      allCourseProgramDetailsWithRatingDF) = contentDataFrames(orgDF)

    val assessmentDF = assessmentESDataFrame()
    val assessmentWithOrgDF = assessWithOrgDataFrame(assessmentDF, orgDF)
    val assessWithHierarchyDF = assessWithHierarchyDataFrame(assessmentWithOrgDF, hierarchyDF)
    val assessWithDetailsDF = assessWithHierarchyDF.drop("children")
    // kafka dispatch to dashboard.assessment
    kafkaDispatch(withTimestamp(assessWithDetailsDF, timestamp), conf.assessmentTopic)

    val userAssessmentDF = userAssessmentDataFrame()
    // do assess + user + course de-norm
    val userAssessmentDetailsDF = userAssessmentDetailsDataFrame(userAssessmentDF, assessWithDetailsDF, allCourseProgramDetailsWithRatingDF, userOrgDF)
    // kafka dispatch to dashboard.user.assessment
    kafkaDispatch(withTimestamp(userAssessmentDetailsDF, timestamp), conf.userAssessmentTopic)


    // explode children info also
    // kafka dispatch to dashboard.assess.content

    // filter what is needed in the report
    // write report to blob store

    closeRedisConnect()

  }

}