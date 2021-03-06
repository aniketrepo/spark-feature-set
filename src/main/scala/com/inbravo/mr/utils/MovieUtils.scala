package com.inbravo.mr.utils

import org.apache.spark.sql.SparkSession
import org.apache.spark.SparkContext
import org.apache.spark.sql.DataFrame
import org.apache.spark.rdd.RDD
import java.util.Scanner
import org.apache.spark.mllib.recommendation.{ ALS, MatrixFactorizationModel, Rating }
import com.inbravo.mr.config.ProjectConfig

/**
 * amit.dixit
 *
 * Movie related functions
 */
object MovieUtils extends ProjectConfig {

  val moviesDF = "movies"
  val genres = "genres"
  val genereFantasy = "Fantasy"
  val genereAction = "Action"
  val genereAnimation = "Animation"
  val genereDrama = "Drama"
  val genereWar = "War"
  val genereHorror = "Horror"
  val genereCrime = "Crime"
  val genereMystery = "Mystery"
  val genereAdventure = "Adventure"

  /**
   *  Get RDD of all movies
   */
  def moviesRDD(sparkSession: SparkSession): RDD[String] = {

    sparkSession.sparkContext.textFile(moviesDataFile)
  }

  /**
   *  Get first ten movies from list
   */
  def firstTenMoviesRDD(sparkSession: SparkSession): Array[String] = {

    /* Get top ten movies */
    sparkSession.sparkContext.textFile(moviesDataFile).filter(FileUtils.fileHeader).collect.take(10)
  }

  /**
   *  Get DataFrame of all movies
   */
  def moviesDF(sparkSession: SparkSession): DataFrame = {

    /* Select movies, group by generes, count number of records,  */
    sparkSession.sql("select * from " + moviesDF)
  }

  /**
   *  Count the number of movies
   */
  def moviesCount(sparkSession: SparkSession): Long = {

    /* Select movies, group by generes, count number of records  */
    sparkSession.sql("select * from " + moviesDF).count
  }

  /**
   *  Count the number of movies of a perticular genre
   */
  def moviesCountByGenre(sparkSession: SparkSession, genre: String): Long = {

    /* Select movies, group by generes, count number of records, filter out unknown genres  */
    sparkSession.sql("select * from " + moviesDF).groupBy(genres).count.filter(row => row.apply(0).toString.contains(genre)).toDF.count
  }

  /**
   *  Get DataFrame of all movies of a perticular genre
   */
  def moviesDFByGenre(sparkSession: SparkSession, genre: String): DataFrame = {

    /* Select movies, group by generes, count number of records, filter out unknown genres */
    sparkSession.sql("select * from " + moviesDF).filter(column => column.apply(2).toString.contains(genre)).toDF
  }

  /**
   *  Get RDD of all movies of a perticular genre
   */
  def moviesRDDByGenre(sparkSession: SparkSession, genre: String): Array[String] = {

    /* Select movies, filter out unknown genres */
    sparkSession.sparkContext.textFile(moviesDataFile).filter(column => column.split(",").apply(2).contains(genre)).filter(FileUtils.fileHeader).collect
  }

  /**
   *  Get Map of all movies <Movie-Id : Movie-Name>
   */
  def moviesMap(sparkSession: SparkSession): Map[Int, String] = {

    /* Discard file header before collecting movies as map */
    moviesRDD(sparkSession).filter(FileUtils.fileHeader).map {
      line =>
        val fields = line.split(",")
        (fields(0).toInt, fields(1))
    }.collect.toMap
  }

  /**
   * Get top rated movies
   */
  def topMovies(sparkSession: SparkSession): List[(Int, String)] = {

    /* Get top rated movies */
    val topRatedMovies = MovieRatingUtils.ratingsRDD(sparkSession).map { rating => rating._2.product }.countByValue.toList.sortBy(-_._2).map { ratingData => ratingData._1 }

    /* Get movie map */
    val movieMap = moviesMap(sparkSession)

    /* Get top movies after sorting */
    topRatedMovies.filter(id => movieMap.contains(id)).map { movieId => (movieId, movieMap.getOrElse(movieId, "No Movie Found")) }.sorted
  }

  /**
   * Get top 10 movies
   */
  def topTenMovies(sparkSession: SparkSession): List[(Int, String)] = {

    /* Get ratings of movies */
    val top50MovieIds = MovieRatingUtils.ratingsRDD(sparkSession).map { rating => rating._2.product }.countByValue.toList.sortBy(-_._2).take(50).map { ratingData => ratingData._1 }

    /* Get movie map */
    val movieMap = moviesMap(sparkSession)

    /* Get top movies after sorting */
    top50MovieIds.filter(id => movieMap.contains(id)).map { movieId => (movieId, movieMap.getOrElse(movieId, "No Movie Found")) }.sorted.take(10)
  }

  /**
   * TODO : Get top rated movies of 100 users
   */
  def topRatedMoviesOf100Users(sparkSession: SparkSession): List[(Int, String)] = {

    /* Get top rated movies */
    val topRatedMovies = MovieRatingUtils.ratingsRDD(sparkSession).map { rating => rating._2.product }.countByValue.toList.sortBy(-_._2).map { ratingData => ratingData._1 }

    /* Get movie map */
    val movieMap = moviesMap(sparkSession)

    /* Get top movies after sorting */
    topRatedMovies.filter(id => movieMap.contains(id)).map { movieId => (movieId, movieMap.getOrElse(movieId, "No Movie Found")) }.sorted
  }
  /**
   * Get user rating for top 10 movies
   */
  def getRatingFromUser(sparkSession: SparkSession): RDD[Rating] = {

    val scanner = new Scanner(System.in)

    /* Get user input for top 10 movies */
    val listOFRating = topTenMovies(sparkSession).map { getRating =>
      {
        println(s"Please Enter The Rating For Movie ${getRating._2} From 1 to 5 [0 if not Seen]")
        Rating(0, getRating._1, scanner.next().toLong)
      }
    }

    scanner.close
    sparkSession.sparkContext.parallelize(listOFRating)
  }

  /**
   * Get movies recommendation
   */
  def recommendMovies(sparkSession: SparkSession): Unit = {

    /* Load and parse ratings data */
    val ratingsData = sparkSession.sparkContext.textFile(ratingsDataFile)

    /* Each record split by comma (,) */
    val ratings = ratingsData.filter(FileUtils.fileHeader).map(_.split(",") match {

      /* If data is 4 dimensional array, create spark ml Rating */
      case Array(user, item, rate, timestamp) => Rating(user.toInt, item.toInt, rate.toDouble)
    })

    val movies = moviesRDD(sparkSession).filter(FileUtils.fileHeader).map {

      /* Split each movie record by comma (,)*/
      str =>
        val data: Array[String] = str.split(",")

        /* Get only first two record of array and create string back */
        (data(0), data(1))
    }.map {
      case (movieId: String, movieName: String) => (movieId.toInt, movieName)
    }

    /* Create trainings */
    val training = ratings.filter { case Rating(userId, movieId, rating) => (userId * movieId) % 10 <= 3 }.persist
    val test = ratings.filter { case Rating(userId, movieId, rating) => (userId * movieId) % 10 > 3 }.persist

    /* Get top 10 movie ratings from user */
    val myRatingsRDD = getRatingFromUser(sparkSession)

    /* Train your model */
    val model = ALS.train(training.union(myRatingsRDD), 8, 10, 0.01)

    /* Get all my seen movies */
    val moviesIHaveSeen = myRatingsRDD.map(x => x.product).collect().toList

    /* Get all my unseen movies */
    val moviesIHaveNotSeen = movies.filter { case (movieId, name) => !moviesIHaveSeen.contains(movieId) }.map(_._1)

    /* Predict Rates */
    val predictedRates = model.predict(test.map {

      /* <User:Item:Rating> => <User:Item> */
      case Rating(user, item, rating) => (user, item)
    }).map {

      /* <User:Product:Rate> => <<User:Product>:Rate> */
      case Rating(user, product, rate) => ((user, product), rate)
    }.persist

    /* Predict Rate Predictions */
    val ratesAndPreds = test.map {

      /* <User:Product:Rate> => <<User:Product>:Rate> */
      case Rating(user, product, rate) => ((user, product), rate)
    }.join(predictedRates)

    /* Find Mean Square Error */
    val MSE = ratesAndPreds.map { case ((user, product), (r1, r2)) => Math.pow(r1 - r2, 2) }.mean

    println("Mean Squared Error = " + MSE)

    /* Find recommended movies from unseen movies */
    val recommendedMoviesId = model.predict(moviesIHaveNotSeen.map {

      product => (0, product)
    }).map {
      case Rating(user, movie, rating) => (movie, rating)
    }.sortBy(x => x._2, ascending = false).take(20).map(x => x._1)

    val recommendMovie = moviesRDD(sparkSession).filter(FileUtils.fileHeader).map {
      str =>
        val data = str.split(",")
        (data(0).toInt, data(1))
    }.filter { case (id, movie) => recommendedMoviesId.contains(id) }

    /* Print all recommended movies */
    recommendMovie.collect.toList.foreach(println)
  }
}