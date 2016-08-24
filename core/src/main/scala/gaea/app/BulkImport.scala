package gaea.app

import org.json4s.native.JsonMethods.parse
import scala.io.Source
import gaea.client.{GaeaClient,ConnectionConfig}


object BulkImport {
  def main(args: Array[String]) = {
    var config = new ConnectionConfig().Kafka(args(0))
    val conn = new GaeaClient(config)

    Source.fromFile(args(1)).getLines().foreach( x => {
      val y = parse(x)
      conn.addMessage(y)
    })

    conn.close()
  }

}
