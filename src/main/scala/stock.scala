
import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.client.RequestBuilding.Get
import akka.http.scaladsl.model.HttpMethods.GET
import akka.http.scaladsl.model.headers.{Authorization, GenericHttpCredentials, RawHeader}
import akka.routing.RoundRobinPool
import akka.stream.ActorMaterializer
import spray.json._
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}
import scala.collection.immutable.Seq

// Case classes for JSON processing

// Prices
case class Quote(close: List[Float], high: List[Float], low: List[Float], open: List[Float], volume: List[Float])
case class Indicators (quote: List[Quote])
case class Result(indicators: Indicators, timestamp: List[Int])
case class Chart(result: List[Result])
case class EquityInfo(chart: Chart)

// Tweets
case class Tweet(id: String, text: String)
case class TweetsResponse(data: List[Tweet])

// Telegram
case class NotificationMessage (chat_id: BigInt = -698158542, text: String)

// trait protocol Prices
trait EquityInfoProtocol extends DefaultJsonProtocol {
  implicit val quoteFormat = jsonFormat5(Quote)
  implicit val indicatorsFormat = jsonFormat1(Indicators)
  implicit val resultFormat = jsonFormat2(Result)
  implicit val chartFormat = jsonFormat1(Chart)
  implicit val equityFormat = jsonFormat1(EquityInfo)
  implicit val tweetFormat = jsonFormat2(Tweet)
  implicit val tweetsResponseFormat = jsonFormat1(TweetsResponse)
}

// trait Telegram
trait TelegramProtocol extends DefaultJsonProtocol {
  implicit val messageFormat = jsonFormat2(NotificationMessage)
}


// main body
object stock extends App with EquityInfoProtocol with TelegramProtocol{
  implicit val system = ActorSystem("StockPriceHype")
  implicit val materializer = ActorMaterializer()
  import system.dispatcher

  // hard-code threshold for price deviation check
  val threshold = 0.05

  // hard-code assets list
  val symbolList = List("AAPL", "MSFT", "GOOG", "AMZN", "TSLA", "FB", "NVDA", "ASML", "ADBE", "NTES", "NFLX", "AVGO", "CSCO", "COST", "PEP", "CMCSA", "PYPL", "QCOM", "INTC", "INTU", "TXN", "AZN", "AMD", "TMUS", "HON", "SBUX", "AMAT", "CHTR", "SNY", "AMGN", "ISRG", "JD", "MRNA", "RIVN", "ABNB", "ADP", "LRCX", "MU", "ADI", "TEAM", "MDLZ", "BKNG", "GILD", "CME", "CSX", "PDD", "EQIX", "MRVL", "REGN")
  system.log.info("Starting gathering data...")

  // actor which do all job
  class Slave extends Actor with ActorLogging {

    override def receive: Receive = {

      case message => {
        log.info(message.toString)

        // вызов API
        val response = Http().singleRequest(HttpRequest(uri = "https://query1.finance.yahoo.com/v7/finance/chart/" + message.toString + "?range=1d&interval=15m"))
        val entityFuture: Future[String] =
          response.flatMap(_.entity.toStrict(10 seconds).map(_.data.utf8String))

        entityFuture.onComplete {

          case Success(body) =>

            // get data from JSON
            val info = body.parseJson.convertTo[EquityInfo].chart.result(0).indicators.quote(0)
            // deviation calculation - assume that we compare last and fists observation in time series
            val change = info.close.last / info.open.head -1
            log.info(f"$message - diff is ${change*100}%.2f%%")

            // threshold control - if higher - lets check twitter and news
            if (math.abs(change) > threshold ) {

              // Request tweets here
              val bearer_token = "{INSERT-HERE-BEARER-TOKEN}"
              val twitter_api_endpoint = f"https://api.twitter.com/2/tweets/search/recent"
              val twitter_auth_header = RawHeader("Authorization", "Bearer " + bearer_token)

              log.info("Twitter request")
              val tweets_request = HttpRequest(GET, twitter_api_endpoint + f"?query=$message", headers = Seq(twitter_auth_header))

              val tweets_response = Http().singleRequest(tweets_request)
              val tweets_entityFuture: Future[String] =
                tweets_response.flatMap(_.entity.toStrict(10 seconds).map(_.data.utf8String))

              tweets_entityFuture.onComplete {

                case Success(body) =>

                  val tweets_response = body.parseJson.convertTo[TweetsResponse]

                  log.info("Notifying Telegram")

                  // Twitter links
                  val tweetBaseLink = "https://twitter.com/i/web/status/"
                  val tweet1Link = tweetBaseLink + tweets_response.data(0).id
                  val tweet2Link = tweetBaseLink + tweets_response.data(1).id
                  val tweet3Link = tweetBaseLink + tweets_response.data(2).id

                  // Telegram message
                  val notificationText =
                    f"""✨ ATTENTION ✨
                       |$message changes is ${change*100}%.2f%%
                       |Recent news: https://finance.yahoo.com/quote/$message/news
                       |Recent tweets:
                       |1 $tweet1Link
                       |2 $tweet2Link
                       |3 $tweet3Link
                       |""".stripMargin

                  val notificationMessage = new NotificationMessage(chat_id = -698158542, text = notificationText)
                  val notification = Http().singleRequest(HttpRequest(
                    method = HttpMethods.POST,
                    uri = "https://api.telegram.org/{INSERT-HERE-TELEGRAM-TOKEN}/sendMessage",
                    entity = HttpEntity(ContentTypes.`application/json`,
                      notificationMessage.toJson.prettyPrint )
                  )
                  )
              }
            }
          case Failure(_) =>
            log.error(s"$message FAILED")
        }
      }
    }
  }

  // actor pool
  val poolMaster = system.actorOf(RoundRobinPool(5).props(Props[Slave]), "simplePoolMaster")

  // pool start
  system.scheduler.schedule(0 second, 5 minute) {
    println(" STARTING CHECK PRICES ...")
    symbolList.take(30).foreach {
      poolMaster !
    }
  }
}