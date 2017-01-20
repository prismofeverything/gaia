package gaia.ingest

/**
  * Created by ellrott on 12/18/16.
  */

import gaia.io.JsonIO
import gaia.schema.Protograph
import gaia.schema.Protograph.{FieldAction, MessageConvert}

import java.io.FileInputStream
import com.google.protobuf.util.JsonFormat
import scala.collection.mutable
import collection.JavaConverters._

import org.yaml.snakeyaml.Yaml
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.databind.ObjectMapper

case class MessageVertexQuery(queryField: String, edgeLabel: String, dstLabel: String)
// case class FieldProcessor(name: String)
// case class MessageConverter(label: String, gidFormat: String, processors: List[])

class ProtographMessageParser(val convert: MessageConvert) {
  def gid(msg: Map[String,Any]): String = {
    // if (convert == null) {
    //   return "gid"
    // }
    // if (convert.getGidFormat.getFieldSelection == null) {
    //   return "gid"
    // }
    println(msg.toString)
    
    msg.get(convert.gidFormat.format).get.asInstanceOf[String]
  }

  /// List out the edge creation requests
  /// These provide the query format, which needs to be searched on the graph to
  /// find unique matches to determine the destination vertex for the edges to be
  /// created
  def destinations(): Iterator[MessageVertexQuery] = {
    // if (convert == null) {
    //   return Iterator[MessageVertexQuery]()
    // }
    convert.getProcessList.asScala.filter( x => x.getAction == FieldAction.CREATE_EDGE ).map { x => 
      new MessageVertexQuery(x.getName, x.getEdgeCreator.getEdgeType, x.getEdgeCreator.getDstMessageType)
    }.toIterator
  }

  /// Create additional vertices that encoded inside of the message
  def children(): Iterator[MessageVertexQuery] = {
    // if (convert == null) {
    //   return Iterator[MessageVertexQuery]()
    // }
    convert.getProcessList.asScala.filter( x => x.getAction == FieldAction.CREATE_LINKED_VERTEX ).map{ x =>
      new MessageVertexQuery(x.getName, x.getEdgeCreator.getEdgeType, x.getEdgeCreator.getDstMessageType)
    }.toIterator
  }

  /// For a given field name, determine the action to be taken
  def fieldActionFor(name: String): FieldAction = {
    // if (convert == null) return FieldAction.NOTHING
    if (name == "#type") {
      FieldAction.NOTHING
    } else {
      val o = convert.getProcessList.asScala.filter(x => x.getName == name).toList
      if (o.size == 0) {
        FieldAction.STORE
      } else {
        o.head.getAction
      }
    }
  }
}

class Protographer(converters: List[MessageConvert]) {
  val converterMap = converters.map(x => (x.getType, x)).toMap

  def converterFor(typ: String): ProtographMessageParser = {
    new ProtographMessageParser(converterMap.getOrElse(typ, null))
  }
}

object Protographer {
  def parseJSON(message: String): MessageConvert = {
    val b = MessageConvert.newBuilder()
    val parser = JsonFormat.parser().ignoringUnknownFields()
    parser.merge(message, b)
    b.build()
  }

  def load(path: String): Protographer = {
    val mapper = new ObjectMapper()

    val yaml = new Yaml()
    val obj = yaml.load(new FileInputStream(path)).asInstanceOf[java.util.ArrayList[Any]]
    val mlist = obj.asScala.map{ x =>
      val s = mapper.writeValueAsString(x)
      parseJSON(s)
    }

    new Protographer(mlist.toList)
  }
}
