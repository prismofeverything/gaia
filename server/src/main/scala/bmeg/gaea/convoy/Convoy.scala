package bmeg.gaea.convoy

import bmeg.gaea.titan.Titan
import bmeg.gaea.schema.Variant
import bmeg.gaea.feature.Feature

import com.thinkaurelius.titan.core.TitanGraph
import gremlin.scala._
import scala.collection.JavaConverters._

// RNASeq.geneExp
// *.maf
// .patient.tsv

object Convoy {
  val titanStringKeys = List(
    "name",
    "source",
    "symbol",

    // variant keys
    "variantClassification",
    "referenceAllele",
    "normalAllele1",
    "normalAllele2",
    "tumorAllele1",
    "tumorAllele2",

    // variant effect keys
    "variantType",
    "transcriptSpecies",
    "transcriptName",
    "transcriptSource",
    "transcriptStatus",
    "transcriptVersion",
    "cPosition",
    "aminoAcidChange",
    "strand",
    "reference",

    // patient keys
    "cancer",
    "icd10",
    "residualTumor",
    "tumorStage",
    "icdO3Site",
    "bcrAliquotUuid",
    "personNeoplasmCancerStatus",
    "bcrPatientUuid",
    "distantMetastasisPathologicSpread",
    "vitalStatus",
    "ajccCancerStagingHandbookEdition",
    "primaryTumorPathologicSpread",
    "histologicalType",
    "icdO3Histology",
    "barcode",
    "lymphnodePathologicSpread",
    "gender",
    "anatomicSiteColorectal",
    "patientId",
    "mononucleotideAndDinucleotideMarkerPanelAnalysisStatus",
    "bcrPatientBarcode",
    "tumorTissueSite",
    "tissueSourceSite",
    "KRAS:mutation",
    "EGFR:mutation",
    "BRAF:mutation",
    "ALK:mutation",
    "NRAS:mutation",
    "ERBB2:mutation"
  )

  val titanBooleanKeys = List(
    // patient keys
    "primaryLymphNodePresentationAssessment",
    "pretreatmentHistory",
    "krasGeneAnalysisPerformed",
    "informedConsentVerified",
    "lossExpressionOfMismatchRepairProteinsByIhc",
    "priorDiagnosis",
    "historyOfColonPolyps",
    "lymphaticInvasion",
    "brafGeneAnalysisPerformed",
    "venousInvasion",
    "microsatelliteInstability",
    "synchronousColonCancerPresent",
    "perineuralInvasionPresent",
    "nonNodalTumorDeposits",
    "colonPolypsPresent"
  )

  val titanLongKeys = List(
    // position keys
    "start",
    "end",

    // patient keys
    "yearOfFormCompletion",
    "monthOfFormCompletion",
    "dayOfFormCompletion",
    "daysToBirth",
    "yearOfInitialPathologicDiagnosis",
    "daysToInitialPathologicDiagnosis",
    "daysToLastFollowup",
    "daysToLastKnownAlive",
    "ageAtInitialPathologicDiagnosis",
    "numberOfFirstDegreeRelativesWithCancerDiagnosis",
    "lymphNodeExaminedCount",
    "numberOfLymphnodesPositiveByHe",
    "circumferentialResectionMargin",
    "height",
    "weight"
  )

  val titanDoubleKeys = List(
    "preoperativePretreatmentCeaLevel"
  )

  val indexSpec = Map(
    "positionIndex" -> Map(
      "reference" -> classOf[String],
      "strand" -> classOf[String],
      "start" -> classOf[Long],
      "end" -> classOf[Long]),
    "nameIndex" -> Map("name" -> classOf[String]),
    "symbolIndex" -> Map("symbol" -> classOf[String]),
    "genderIndex" -> Map("gender" -> classOf[String]),
    "cancerIndex" -> Map("cancer" -> classOf[String])
  )

  val keys: Map[String, Key[String]] = titanStringKeys.foldLeft(Map[String, Key[String]]()) {(m, s) =>
    m + (s -> Key[String](s))
  }

  val nkeys: Map[String, Key[Long]] = titanLongKeys.foldLeft(Map[String, Key[Long]]()) {(m, s) =>
    m + (s -> Key[Long](s))
  }

  val bkeys: Map[String, Key[Boolean]] = titanBooleanKeys.foldLeft(Map[String, Key[Boolean]]()) {(m, s) =>
    m + (s -> Key[Boolean](s))
  }

  val dkeys: Map[String, Key[Double]] = titanDoubleKeys.foldLeft(Map[String, Key[Double]]()) {(m, s) =>
    m + (s -> Key[Double](s))
  }

  def camelize(s: String): String = {
    val break = s.split("_")
    val upper = break.head +: break.tail.map(_.capitalize)
    upper.mkString("")
  }

  def ingestPosition(graph: TitanGraph) (position: Variant.Position): Vertex = {
    graph.V.hasLabel("position")
      .has(keys("reference"), position.getReference())
      .has(keys("strand"), position.getStrand())
      .has(nkeys("start"), position.getStart().asInstanceOf[Long])
      .has(nkeys("end"), position.getEnd().asInstanceOf[Long])
      .headOption.getOrElse {
      graph + ("position",
        keys("reference") -> position.getReference(),
        keys("strand") -> position.getStrand(),
        nkeys("start") -> position.getStart().asInstanceOf[Long],
        nkeys("end") -> position.getEnd().asInstanceOf[Long])
    }
  }

  def ingestDomain(graph: TitanGraph) (domain: String): Vertex = {
    graph.V.hasLabel("domain").has(keys("name"), domain).headOption.getOrElse {
      graph + ("domain", keys("name") -> domain)
    }
  }

  def ingestVariantCallEffect(graph: TitanGraph) (callEffect: Variant.VariantCallEffect): Vertex = {
    val callEffectVertex = graph + ("variantCallEffect",
      keys("variantType") -> callEffect.getVariantType(),
      keys("transcriptSpecies") -> callEffect.getTranscriptSpecies(),
      keys("transcriptName") -> callEffect.getTranscriptName(),
      keys("transcriptSource") -> callEffect.getTranscriptSource(),
      keys("transcriptStatus") -> callEffect.getTranscriptStatus(),
      keys("transcriptVersion") -> callEffect.getTranscriptVersion(),
      keys("cPosition") -> callEffect.getCPosition(),
      keys("aminoAcidChange") -> callEffect.getAminoAcidChange(),
      keys("strand") -> callEffect.getStrand()
    )

    val feature = callEffect.getFeature()
    val featureVertex = Feature.findFeature(graph) (feature)

    featureVertex --- ("hasEffect") --> callEffectVertex

    val domains = callEffect.getDomainsList().asScala.toList
    val domainVertexes = domains.map(ingestDomain(graph))
    for (domainVertex <- domainVertexes) {
      domainVertex <-- ("inDomain") --- callEffectVertex
    }

    callEffectVertex
  }

  def ingestVariantCall(graph: TitanGraph) (source: String) (variantCall: Variant.VariantCall): Vertex = {
    val positionVertex = ingestPosition(graph) (variantCall.getPosition())
    val variantCallVertex = graph + ("variantCall",
      keys("source") -> source,
      keys("variantClassification") -> variantCall.getVariantClassification(),
      keys("referenceAllele") -> variantCall.getReferenceAllele(),
      keys("normalAllele1") -> variantCall.getNormalAllele1(),
      keys("normalAllele2") -> variantCall.getNormalAllele2(),
      keys("tumorAllele1") -> variantCall.getTumorAllele1(),
      keys("tumorAllele2") -> variantCall.getTumorAllele2()
    )

    val callEffects = variantCall.getVariantCallEffectsList().asScala.toList
    val callEffectVertexes = callEffects.map(ingestVariantCallEffect(graph))
    for (callEffectVertex <- callEffectVertexes) {
      callEffectVertex --- ("inCall") --> variantCallVertex
    }

    variantCallVertex --- ("atPosition") --> positionVertex
    variantCallVertex
  }

  def ingestBioSample(graph: TitanGraph) (source: String) (bioSample: Variant.BioSample): Vertex = {
    val bioSampleVertex = graph.V.hasLabel("bioSample").has(keys("name"), bioSample.getName()).headOption.getOrElse {
      graph + ("bioSample",
        keys("name") -> bioSample.getName(),
        keys("source") -> source
      )
    }

    val variantCalls = bioSample.getVariantCallsList().asScala.toList
    val variantCallVertexes = variantCalls.map(ingestVariantCall(graph) (source))
    for (variantCallVertex <- variantCallVertexes) {
      bioSampleVertex --- ("hasVariant") --> variantCallVertex
    }

    bioSampleVertex
  }

  def ingestIndividual(graph: TitanGraph) (individual: Variant.Individual): Vertex = {
    val source = individual.getSource()
    val individualVertex = graph.V.hasLabel("individual").has(keys("name"), individual.getName()).headOption.getOrElse {
      graph + ("individual",
        keys("name") -> individual.getName(),
        keys("source") -> source
      )
    }

    val observations = individual.getObservations().asScala
    for ((key, observation) <- observations) {
      val observationKey = camelize(key)
      println(observationKey)
      if (nkeys.contains(observationKey)) {
        val raw: String = observation.split("\\.").head
        val n: Long = java.lang.Long.parseLong(raw)
        individualVertex.setProperty(nkeys(observationKey), n)
      } else if (dkeys.contains(observationKey)) {
        val n: Double = java.lang.Double.parseDouble(observation)
        individualVertex.setProperty(dkeys(observationKey), n)
      } else if (bkeys.contains(observationKey)) {
        val bool: Boolean = observation == "YES"
        individualVertex.setProperty(bkeys(observationKey), bool)
      } else {
        individualVertex.setProperty(keys.get(observationKey).getOrElse {
          Key[String](observationKey)
        }, observation)
      }
    }

    val bioSamples = individual.getBioSamplesList().asScala.toList
    val bioSampleVertexes = bioSamples.map(ingestBioSample(graph) (source))
    for (bioSampleVertex <- bioSampleVertexes) {
      individualVertex --- ("hasSample") --> bioSampleVertex
    }

    individualVertex
  }

  def ingestIndividualList(individualList: Variant.IndividualList): Int = {
    val individuals = individualList.getIndividualsList().asScala.toList
    val graph = Titan.connect(Titan.configuration())
    println(s"Ingesting ${individuals.length} individuals")

    val individualVertexes = individuals.map(ingestIndividual(graph))
    graph.tx.commit()
    individualVertexes.length
  }
}
