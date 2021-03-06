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

package org.apache.spark.sql.catalyst.expressions

import com.clearspring.analytics.stream.cardinality.HyperLogLog
import org.apache.spark.Logging
import org.apache.spark.sql.types._
import org.apache.spark.sql.catalyst.trees
import org.apache.spark.sql.catalyst.errors.TreeNodeException
import org.apache.spark.sql.catalyst.util.commonMath
import org.apache.spark.util.collection.OpenHashSet
import scala.collection.mutable.ListBuffer


class OnlineResult(result: Any, confidence: Double, bound: Double) {


  override def toString: String = s"$result # $confidence # $bound"

}

// todo transfer size and confidence variable
case class OnlineMin(child: Expression) extends PartialAggregate with trees.UnaryNode[Expression] {

  println("onlineMin initialize ")

  override def nullable = true

  // override def dataType = child.dataType
  override def dataType: DataType = StringType

  override def toString = s"OnlineMIN($child)"

  override def asPartial: SplitEvaluation = {
    val partialMin = Alias(Min(child), "PartialMin")()
    SplitEvaluation(Min(partialMin.toAttribute), partialMin :: Nil)
  }

  override def newInstance() = new OnlineMinFunction(child, this)
}

// change the eval out to a OnlineResult object
case class OnlineMinFunction(expr: Expression, base: AggregateExpression)
  extends AggregateFunction with Logging {
  logError("onlineMin Func initialize ")

  def this() = this(null, null) // Required for serialization.

  var mockInternal = 0.8
  var mockZone = 0.8

  val currentMin: MutableLiteral = MutableLiteral(null, expr.dataType)
  val cmp = GreaterThan(currentMin, expr)

  override def update(input: Row): Unit = {
    if (currentMin.value == null) {
      currentMin.value = expr.eval(input)
    } else if (cmp.eval(input) == true) {
      currentMin.value = expr.eval(input)
    }
    logError("come into online aggregate update")
  }

  override def eval(input: Row): Any = {
    logError("come into online aggregate update")
    s"1 # $mockInternal # $mockZone".asInstanceOf[StringType]
  }

  // override def eval(input: Row): Any =
  // new OnlineResult(currentMin.value, mockInternal, mockZone)

}

case class OnlineMax(child: Expression) extends PartialAggregate with trees.UnaryNode[Expression] {

  override def nullable = true

  override def dataType = child.dataType

  override def toString = s"MAX($child)"

  override def asPartial: SplitEvaluation = {
    val partialMax = Alias(Max(child), "PartialMax")()
    SplitEvaluation(Max(partialMax.toAttribute), partialMax :: Nil)
  }

  override def newInstance() = new OnlineMaxFunction(child, this)
}

case class OnlineMaxFunction(expr: Expression, base: AggregateExpression)
  extends AggregateFunction {
  def this() = this(null, null) // Required for serialization.

  val currentMax: MutableLiteral = MutableLiteral(null, expr.dataType)
  val cmp = LessThan(currentMax, expr)

  override def update(input: Row): Unit = {
    if (currentMax.value == null) {
      currentMax.value = expr.eval(input)
    } else if (cmp.eval(input) == true) {
      currentMax.value = expr.eval(input)
    }
  }

  override def eval(input: Row): Any = currentMax.value
}

case class OnlineCount(child: Expression)
  extends PartialAggregate with trees.UnaryNode[Expression] {

  override def nullable = false

  override def dataType = LongType

  override def toString = s"COUNT($child)"

  override def asPartial: SplitEvaluation = {
    val partialCount = Alias(Count(child), "PartialCount")()
    SplitEvaluation(Coalesce(Seq(Sum(partialCount.toAttribute), Literal(0L))), partialCount :: Nil)
  }

  override def newInstance() = new OnlineCountFunction(child, this)
}

case class OnlineCountDistinct(expressions: Seq[Expression]) extends PartialAggregate {
  def this() = this(null)

  override def children = expressions

  override def nullable = false

  override def dataType = LongType

  override def toString = s"COUNT(DISTINCT ${expressions.mkString(",")})"

  override def newInstance() = new CountDistinctFunction(expressions, this)

  override def asPartial = {
    val partialSet = Alias(CollectHashSet(expressions), "partialSets")()
    SplitEvaluation(
      CombineSetsAndCount(partialSet.toAttribute),
      partialSet :: Nil)
  }
}

case class OnlineCollectHashSet(expressions: Seq[Expression]) extends AggregateExpression {
  def this() = this(null)

  override def children = expressions

  override def nullable = false

  override def dataType = ArrayType(expressions.head.dataType)

  override def toString = s"AddToHashSet(${expressions.mkString(",")})"

  override def newInstance() = new OnlineCollectHashSetFunction(expressions, this)
}

case class OnlineCollectHashSetFunction(
                                         @transient expr: Seq[Expression],
                                         @transient base: AggregateExpression)
  extends AggregateFunction {

  def this() = this(null, null) // Required for serialization.

  val seen = new OpenHashSet[Any]()

  @transient
  val distinctValue = new InterpretedProjection(expr)

  override def update(input: Row): Unit = {
    val evaluatedExpr = distinctValue(input)
    if (!evaluatedExpr.anyNull) {
      seen.add(evaluatedExpr)
    }
  }

  override def eval(input: Row): Any = {
    seen
  }
}

case class OnlineCombineSetsAndCount(inputSet: Expression) extends AggregateExpression {
  def this() = this(null)

  override def children = inputSet :: Nil

  override def nullable = false

  override def dataType = LongType

  override def toString = s"CombineAndCount($inputSet)"

  override def newInstance() = new OnlineCombineSetsAndCountFunction(inputSet, this)
}

case class OnlineCombineSetsAndCountFunction(
                                              @transient inputSet: Expression,
                                              @transient base: AggregateExpression)
  extends AggregateFunction {

  def this() = this(null, null) // Required for serialization.

  val seen = new OpenHashSet[Any]()

  override def update(input: Row): Unit = {
    val inputSetEval = inputSet.eval(input).asInstanceOf[OpenHashSet[Any]]
    val inputIterator = inputSetEval.iterator
    while (inputIterator.hasNext) {
      seen.add(inputIterator.next)
    }
  }

  override def eval(input: Row): Any = seen.size.toLong
}


case class OnlineAverage(child: Expression)
  extends PartialAggregate with trees.UnaryNode[Expression] {

  override def nullable = true

  override def dataType = child.dataType match {
    case DecimalType.Fixed(precision, scale) =>
      DecimalType(precision + 4, scale + 4) // Add 4 digits after decimal point, like Hive
    case DecimalType.Unlimited =>
      DecimalType.Unlimited
    case _ =>
      DoubleType
  }

  override def toString = s"AVG($child)"

  override def asPartial: SplitEvaluation = {
    child.dataType match {
      case DecimalType.Fixed(_, _) =>
        // Turn the child to unlimited decimals for calculation, before going back to fixed
        val partialSum = Alias(Sum(Cast(child, DecimalType.Unlimited)), "PartialSum")()
        val partialCount = Alias(Count(child), "PartialCount")()

        val castedSum = Cast(Sum(partialSum.toAttribute), DecimalType.Unlimited)
        val castedCount = Cast(Sum(partialCount.toAttribute), DecimalType.Unlimited)
        SplitEvaluation(
          Cast(Divide(castedSum, castedCount), dataType),
          partialCount :: partialSum :: Nil)

      case _ =>
        val partialSum = Alias(Sum(child), "PartialSum")()
        val partialCount = Alias(Count(child), "PartialCount")()

        val castedSum = Cast(Sum(partialSum.toAttribute), dataType)
        val castedCount = Cast(Sum(partialCount.toAttribute), dataType)
        SplitEvaluation(
          Divide(castedSum, castedCount),
          partialCount :: partialSum :: Nil)
    }
  }

  override def newInstance() = new OnlineAverageFunction(child, this)
}

case class OnlineSum(child: Expression) extends PartialAggregate with trees.UnaryNode[Expression] {

  override def nullable = true

  override def dataType = child.dataType match {
    case DecimalType.Fixed(precision, scale) =>
      DecimalType(precision + 10, scale) // Add 10 digits left of decimal point, like Hive
    case DecimalType.Unlimited =>
      DecimalType.Unlimited
    case _ =>
      child.dataType
  }

  override def toString = s"SUM($child)"

  override def asPartial: SplitEvaluation = {
    child.dataType match {
      case DecimalType.Fixed(_, _) =>
        val partialSum = Alias(Sum(Cast(child, DecimalType.Unlimited)), "PartialSum")()
        SplitEvaluation(
          Cast(Sum(partialSum.toAttribute), dataType),
          partialSum :: Nil)

      case _ =>
        val partialSum = Alias(Sum(child), "PartialSum")()
        SplitEvaluation(
          Sum(partialSum.toAttribute),
          partialSum :: Nil)
    }
  }

  override def newInstance() = new OnlineSumFunction(child, this)
}

case class OnlineSumDistinct(child: Expression)
  extends PartialAggregate with trees.UnaryNode[Expression] {

  def this() = this(null)

  override def nullable = true

  override def dataType = child.dataType match {
    case DecimalType.Fixed(precision, scale) =>
      DecimalType(precision + 10, scale) // Add 10 digits left of decimal point, like Hive
    case DecimalType.Unlimited =>
      DecimalType.Unlimited
    case _ =>
      child.dataType
  }

  override def toString = s"SUM(DISTINCT ${child})"

  override def newInstance() = new OnlineSumDistinctFunction(child, this)

  override def asPartial = {
    val partialSet = Alias(CollectHashSet(child :: Nil), "partialSets")()
    SplitEvaluation(
      CombineSetsAndSum(partialSet.toAttribute, this),
      partialSet :: Nil)
  }
}

case class OnlineCombineSetsAndSum(inputSet: Expression, base: Expression)
  extends AggregateExpression {
  def this() = this(null, null)

  override def children = inputSet :: Nil

  override def nullable = true

  override def dataType = base.dataType

  override def toString = s"CombineAndSum($inputSet)"

  override def newInstance() = new OnlineCombineSetsAndSumFunction(inputSet, this)
}

case class OnlineCombineSetsAndSumFunction(
                                            @transient inputSet: Expression,
                                            @transient base: AggregateExpression)
  extends AggregateFunction {

  def this() = this(null, null) // Required for serialization.

  val seen = new OpenHashSet[Any]()

  override def update(input: Row): Unit = {
    val inputSetEval = inputSet.eval(input).asInstanceOf[OpenHashSet[Any]]
    val inputIterator = inputSetEval.iterator
    while (inputIterator.hasNext) {
      seen.add(inputIterator.next)
    }
  }

  override def eval(input: Row): Any = {
    val casted = seen.asInstanceOf[OpenHashSet[Row]]
    if (casted.size == 0) {
      null
    } else {
      Cast(Literal(
        casted.iterator.map(f => f.apply(0)).reduceLeft(
          base.dataType.asInstanceOf[NumericType].numeric.asInstanceOf[Numeric[Any]].plus)),
        base.dataType).eval(null)
    }
  }
}

case class OnlineAverageFunction(expr: Expression, base: AggregateExpression)
  extends AggregateFunction {

  def this() = this(null, null) // Required for serialization.

  private val calcType =
    expr.dataType match {
      case DecimalType.Fixed(_, _) =>
        DecimalType.Unlimited
      case _ =>
        expr.dataType
    }

  private val zero = Cast(Literal(0), calcType)

  private var count: Long = _
  private val sum = MutableLiteral(zero.eval(null), calcType)
  // 每一批数据的大小
  private val batchSize = 100
  // 存储当前批数据
  private var batch = new ListBuffer[Double]()
  // 追踪当前批数据是否已满
  private var batchPivot = 0
  // 截止到上一批数据处理完毕时的平均值和方差
  private var historicalAvg = 0d
  private var historicalVar = 0d

  private def addFunction(value: Any) = Add(sum, Cast(Literal(value, expr.dataType), calcType))

  override def eval(input: Row): Any = {
    var resVal: Any = null

    if (count == 0L) {
      null
    } else {
      resVal = expr.dataType match {
        case DecimalType.Fixed(_, _) =>
          Cast(Divide(
            Cast(sum, DecimalType.Unlimited),
            Cast(Literal(count), DecimalType.Unlimited)), dataType).eval(null)
        case _ =>
          Divide(
            Cast(sum, dataType),
            Cast(Literal(count), dataType)).eval(null)
      }
    }

    updateHistorical()

    val T = math.sqrt(historicalVar)
    val errorBound: Double = 2.0
    val confidence = commonMath.calcConfidence(errorBound, count, T)

    new OnlineResult(resVal, confidence, errorBound)
  }

  def calcBatchVar(): Double = {
    val batchAvg: Double = batch.sum / batch.length
    batch.foldLeft(0d) { case (sum, sample) =>
      sum + (sample - batchAvg) * (sample - batchAvg)
    } / batch.length
  }

  def updateHistorical(): Unit = {
    // batch 满了，结合历史数据的 avg 和 var 增量计算当前的 avg 和 var
    val batchAvg: Double = batch.sum / batch.length
    val batchVar: Double = calcBatchVar()

    val hCount = count - batch.length

    val crtAvg = expr.dataType match {
      case DecimalType.Fixed(_, _) =>
        Cast(Divide(
          Cast(sum, DecimalType.Unlimited),
          Cast(Literal(count), DecimalType.Unlimited)), dataType).eval(null)
      case _ =>
        Divide(
          Cast(sum, dataType),
          Cast(Literal(count), dataType)).eval(null)
    }
    val crtSum = expr.dataType match {
      case DecimalType.Fixed(_, _) =>
        Cast(sum, DecimalType.Unlimited).eval(null)
      case _ =>
        Cast(sum, dataType).eval(null)
    }

    historicalVar = if (hCount == 0) batchVar
    else (
      hCount * (historicalVar + math.pow(crtAvg.asInstanceOf[Double] - historicalAvg, 2.0)) +
        batchSize * (batchVar + math.pow(crtAvg.asInstanceOf[Double] - batchAvg, 2.0))
      ) / (hCount + batchSize)

    historicalAvg = if (hCount == 0) batchAvg
    else (
      crtSum.asInstanceOf[Double] - batch.sum) / (count - batch.length)
  }

  override def update(input: Row): Unit = {
    val evaluatedExpr = expr.eval(input)
    if (evaluatedExpr != null) {
      count += 1
      sum.update(addFunction(evaluatedExpr), input)

      if (batchPivot < batchSize) {
        batch += evaluatedExpr.asInstanceOf[Double]
        batchPivot += 1
      } else {
        updateHistorical()
        // 增量更新完毕，清空 batch
        batch.clear()
        batchPivot = 0
      }
    }
  }
}

case class OnlineCountFunction(expr: Expression, base: AggregateExpression)
  extends AggregateFunction {


  def this() = this(null, null) // Required for serialization.

  var count: Long = _
  var sum_count: Long = _ // table 里面的元组数量

  var sample_count: Long = _ // sample提取的数量

  var probility: Double = 0.8
  var interval: Double = _

  var square_sum: Double = _
  var sum_square: Double = _

  var deta: Double = _

  var probility_interval: Boolean = true

  override def update(input: Row): Unit = {
    val evaluatedExpr = expr.eval(input)
    sample_count += 1L
    if (evaluatedExpr != null) {
      count += 1L
      sum_square = count
      square_sum = square_sum + 1L
      deta = sum_count * sum_count * square_sum -
        sum_count * sum_count * sum_square * sum_square / sample_count / sample_count

      if (probility_interval) {
        probility_interval = false
        interval = (1 + probility) / 2 * math.pow(math.pow(deta, 1 / 2) / sample_count, 1 / 2)
      }
      else {
        probility_interval = true
        probility = interval * math.pow(sample_count, 1 / 2) / math.pow(deta, 1 / 2) * 2 - 1
      }
    }
  }
}

case class OnlineSumFunction(expr: Expression, base: AggregateExpression)
  extends AggregateFunction {
  def this() = this(null, null) // Required for serialization.

  private val calcType =
    expr.dataType match {
      case DecimalType.Fixed(_, _) =>
        DecimalType.Unlimited
      case _ =>
        expr.dataType
    }

  private val zero = Cast(Literal(0), calcType)

  private val sum = MutableLiteral(null, calcType)

  private var count: Long = _
  // 每一批数据的大小
  private val batchSize = 100
  // 存储当前批数据
  private var batch = new ListBuffer[Double]()
  // 追踪当前批数据是否已满
  private var batchPivot = 0
  // 截止到上一批数据处理完毕时的平均值和方差
  private var historicalAvg = 0d
  private var historicalVar = 0d

  // private val sq: Sqrt = Sqrt(expr) // sqrt function

  // private var curValSqrt: Double = 0 // sqrt of current val

  // private var sumSqrt: Double = 0 // sqrt of sum

  // private var varianceSqrt: Double = 0 // sqrt of variance

  // private var curSampleSizeSqrt: Double = 0 // sqrt of size of current sample

  private var tableSizeSqrt: Double = 0 // sqrt of the table


  private val addFunction = Coalesce(Seq(Add(Coalesce(Seq(sum, zero)), Cast(expr, calcType)), sum))

  def calcBatchVar(): Double = {
    val batchAvg: Double = batch.sum / batch.length
    var sampleSqrt : Double = batch.foldLeft(0d) { case (sum, sample) =>
      sum + math.sqrt(sample.asInstanceOf[Double])
    }
    var sampleSumSqrt : Double = math.sqrt(batch.foldLeft(0d) { case (sum, sample) =>
      sum + sample.asInstanceOf[Double]
    } / batch.length)

    var curBatchVar : Double = math.sqrt(tableSizeSqrt) * (sampleSqrt - sampleSumSqrt)

    return curBatchVar
  }

  def updateHistorical(): Unit = {
    // batch 满了，结合历史数据的 avg 和 var 增量计算当前的 avg 和 var
    val batchAvg: Double = batch.sum / batch.length
    val batchVar: Double = calcBatchVar()

    val hCount = count - batch.length

    val crtAvg = expr.dataType match {
      case DecimalType.Fixed(_, _) =>
        Cast(Divide(
          Cast(sum, DecimalType.Unlimited),
          Cast(Literal(count), DecimalType.Unlimited)), dataType).eval(null)
      case _ =>
        Divide(
          Cast(sum, dataType),
          Cast(Literal(count), dataType)).eval(null)
    }
    val crtSum = expr.dataType match {
      case DecimalType.Fixed(_, _) =>
        Cast(sum, DecimalType.Unlimited).eval(null)
      case _ =>
        Cast(sum, dataType).eval(null)
    }

    historicalVar = if (hCount == 0) batchVar
    else (
      hCount * (historicalVar + math.pow(crtAvg.asInstanceOf[Double] - historicalAvg, 2.0)) +
        batchSize * (batchVar + math.pow(crtAvg.asInstanceOf[Double] - batchAvg, 2.0))
      ) / (hCount + batchSize)

    historicalAvg = if (hCount == 0) batchAvg
    else (
      crtSum.asInstanceOf[Double] - batch.sum) / (count - batch.length)
  }

  override def update(input: Row): Unit = {
    // curValSqrt += sq.eval(input).asInstanceOf[Double]
    val evaluatedExpr = expr.eval(input)
    if (evaluatedExpr != null) {
      count += 1
      sum.update(addFunction, input)

      if (batchPivot < batchSize) {
        batch += evaluatedExpr.asInstanceOf[Double]
        batchPivot += 1
      } else {
        updateHistorical()
        // 增量更新完毕，清空 batch
        batch.clear()
        batchPivot = 0
      }
    }

  }

  override def eval(input: Row): Any = {
    var resVal: Any = null

    resVal = expr.dataType match {
      case DecimalType.Fixed(_, _) =>
        Cast(sum, dataType).eval(null)
      case _ => sum.eval(null)
    }

    updateHistorical()

    val T = math.sqrt(historicalVar)
    val errorBound: Double = 2.0
    val confidence = commonMath.calcConfidence(errorBound, count, T)

    new OnlineResult(resVal, confidence, errorBound)

   // sumSqrt = math.sqrt(sum.value.asInstanceOf[Double])
    // varianceSqrt = tableSizeSqrt * (curValSqrt - sumSqrt / curSampleSizeSqrt)
  }
}

case class OnlineSumDistinctFunction(expr: Expression, base: AggregateExpression)
  extends AggregateFunction {

  def this() = this(null, null) // Required for serialization.

  private val seen = new scala.collection.mutable.HashSet[Any]()

  override def update(input: Row): Unit = {
    val evaluatedExpr = expr.eval(input)
    if (evaluatedExpr != null) {
      seen += evaluatedExpr
    }
  }

  override def eval(input: Row): Any = {
    if (seen.size == 0) {
      null
    } else {
      Cast(Literal(
        seen.reduceLeft(
          dataType.asInstanceOf[NumericType].numeric.asInstanceOf[Numeric[Any]].plus)),
        dataType).eval(null)
    }
  }
}

case class OnlineCountDistinctFunction(
                                        @transient expr: Seq[Expression],
                                        @transient base: AggregateExpression)
  extends AggregateFunction {

  def this() = this(null, null) // Required for serialization.

  val seen = new OpenHashSet[Any]()

  @transient
  val distinctValue = new InterpretedProjection(expr)

  override def update(input: Row): Unit = {
    val evaluatedExpr = distinctValue(input)
    if (!evaluatedExpr.anyNull) {
      seen.add(evaluatedExpr)
    }
  }

  override def eval(input: Row): Any = seen.size.toLong

}




