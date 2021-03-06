package sangria.validation.rules

import scala.language.postfixOps

import sangria.ast
import sangria.ast.AstVisitorCommand._
import sangria.renderer.{QueryRenderer, SchemaRenderer}
import sangria.schema._
import sangria.validation._
import scala.collection.mutable.{ListBuffer, Set ⇒ MutableSet, ListMap ⇒ MutableMap}

/**
 * Overlapping fields can be merged
 *
 * A selection set is only valid if all fields (including spreading any
 * fragments) either correspond to distinct response names or can be merged
 * without ambiguity.
 */
class OverlappingFieldsCanBeMerged extends ValidationRule {
  override def visitor(ctx: ValidationContext) = new AstValidatingVisitor {
    val comparedSet = new PairSet[ast.AstNode]

    override val onLeave: ValidationVisit = {
      case selCont: ast.SelectionContainer ⇒
        val fields = collectFieldASTsAndDefs(ctx, ctx.typeInfo.previousParentType, selCont)
        val conflicts = findConflicts(fields, false)

        if (conflicts.nonEmpty)
          Left(conflicts.toVector.map(c ⇒ FieldsConflictViolation(c.reason.fieldName, c.reason.reason, ctx.sourceMapper, c.fields flatMap (_.position))))
        else
          Right(Continue)
    }

    def findConflicts(fieldMap: CollectedFields, parentFieldsAreMutuallyExclusive: Boolean): Seq[Conflict] = {
      val conflicts = ListBuffer[Conflict]()

      fieldMap.keys.foreach { outputName ⇒
        val fields  = fieldMap(outputName)

        if (fields.size > 1)
          for (i ← 0 until fields.size) {
            for (j ← i until fields.size) {
              findConflict(outputName, fields(i), fields(j), parentFieldsAreMutuallyExclusive) match {
                case Some(conflict) ⇒ conflicts += conflict
                case None ⇒ // do nothing
              }
            }
          }
      }

      conflicts
    }

    def findConflict(
        outputName: String,
        fieldInfo1: (Option[Type], ast.Field, Option[Field[_, _]]),
        fieldInfo2: (Option[Type], ast.Field, Option[Field[_, _]]),
        parentFieldsAreMutuallyExclusive: Boolean): Option[Conflict] = {
      val (parentType1, ast1, def1) = fieldInfo1
      val (parentType2, ast2, def2) = fieldInfo2

      // Memoize, do not report the same issue twice.
      // Note: Two overlapping ASTs could be encountered both when
      // `parentFieldsAreMutuallyExclusive` is true and is false, which could
      // produce different results (when `true` being a subset of `false`).
      // However we do not need to include this piece of information when
      // memoizing since this rule visits leaf fields before their parent fields,
      // ensuring that `parentFieldsAreMutuallyExclusive` is `false` the first
      // time two overlapping fields are encountered, ensuring that the full
      // set of validation rules are always checked when necessary.
      if ((ast1 eq ast2) || comparedSet.contains(ast1, ast2)) {
        None
      } else {
        comparedSet.add(ast1, ast2)

        // If it is known that two fields could not possibly apply at the same
        // time, due to the parent types, then it is safe to permit them to diverge
        // in aliased field or arguments used as they will not present any ambiguity
        // by differing.
        // It is known that two parent types could never overlap if they are
        // different Object types. Interface or Union types might overlap - if not
        // in the current state of the schema, then perhaps in some future version,
        // thus may not safely diverge.
        val fieldsAreMutuallyExclusive = parentFieldsAreMutuallyExclusive || ((parentType1, parentType2) match {
          case (Some(pt1: ObjectType[_, _]), Some(pt2: ObjectType[_, _])) if pt1.name != pt2.name ⇒ true
          case _ ⇒ false
        })


        if (!fieldsAreMutuallyExclusive && ast1.name != ast2.name)
          Some(Conflict(ConflictReason(outputName, Left(s"'${ast1.name}' and '${ast2.name}' are different fields")), ast1 :: ast2 :: Nil))
        else if (!fieldsAreMutuallyExclusive && !sameArguments(ast1.arguments, ast2.arguments))
          Some(Conflict(ConflictReason(outputName, Left("they have differing arguments")), ast1 :: ast2 :: Nil))
        else {
          val typeRes = for {
            field1 ← def1
            field2 ← def2
          } yield if (doTypesConflict(field1.fieldType, field2.fieldType)) {
            val type1 = SchemaRenderer.renderTypeName(field1.fieldType)
            val type2 = SchemaRenderer.renderTypeName(field2.fieldType)

            Some(Conflict(ConflictReason(outputName, Left(s"they return conflicting types '$type1' and '$type2'")), ast1 :: ast2 :: Nil))
          } else None

          typeRes.flatten match {
            case s @ Some(_) ⇒ s
            case None ⇒
              subfieldConflicts(findConflicts(getSubfieldMap(ast1, def1, ast2, def2), fieldsAreMutuallyExclusive), outputName, ast1, ast2)
          }
        }
      }
    }

    // Two types conflict if both types could not apply to a value simultaneously.
    // Composite types are ignored as their individual field types will be compared
    // later recursively. However List and Non-Null types must match.
    def doTypesConflict(type1: OutputType[_], type2: OutputType[_]): Boolean = (type1, type2) match {
      case (ListType(ot1), ListType(ot2)) ⇒ doTypesConflict(ot1, ot2)
      case (ListType(_), _) | (_, ListType(_)) ⇒ true
      case (OptionType(ot1), OptionType(ot2)) ⇒ doTypesConflict(ot1, ot2)
      case (OptionType(_), _) | (_, OptionType(_)) ⇒ true
      case (nt1: LeafType, nt2: Named) ⇒ nt1.name != nt2.name
      case (nt1: Named, nt2: LeafType) ⇒ nt1.name != nt2.name
      case _ ⇒ false
    }

    def getSubfieldMap(ast1: ast.Field, def1: Option[Field[_, _]], ast2: ast.Field, def2: Option[Field[_, _]]): CollectedFields = {
      val visitedFragmentNames = MutableSet[String]()
      val subfieldMap1 = collectFieldASTsAndDefs(ctx, def1.map (d ⇒ ctx.typeInfo.getNamedType(d.fieldType)), ast1, visitedFragmentNames)

      collectFieldASTsAndDefs(ctx, def2.map (d ⇒ ctx.typeInfo.getNamedType(d.fieldType)), ast2, visitedFragmentNames, subfieldMap1)
    }

    def subfieldConflicts(conflicts: Seq[Conflict], outputName: String, ast1: ast.Field, ast2: ast.Field): Option[Conflict] =
      if (conflicts.nonEmpty)
        Some(Conflict(ConflictReason(outputName, Right(conflicts map (_.reason) toVector)),
          conflicts.foldLeft(ast1 :: ast2 :: Nil){case (acc, Conflict(_, fields)) ⇒ acc ++ fields}))
      else
        None
  }

  type CollectedFields = MutableMap[String, ListBuffer[(Option[Type], ast.Field, Option[Field[_, _]])]]

  def sameArguments(args1: List[ast.Argument], args2: List[ast.Argument]) =
    if (args1.size != args2.size) false
    else args1.forall { a1 ⇒
      args2.find(_.name == a1.name) match {
        case Some(a2) ⇒ sameValue(a1.value, a2.value)
        case None ⇒ false
      }
    }

  def sameValue(v1: ast.Value, v2: ast.Value) =
    QueryRenderer.render(v1, QueryRenderer.Compact) ==
      QueryRenderer.render(v2, QueryRenderer.Compact)

  /**
    * Given a selectionSet, adds all of the fields in that selection to
    * the passed in map of fields, and returns it at the end.
    *
    * Note: This is not the same as execution's collectFields because at static
    * time we do not know what object type will be used, so we unconditionally
    * spread in all fragments.
    */
  def collectFieldASTsAndDefs(
      ctx: ValidationContext,
      parentType: Option[Type],
      selCont: ast.SelectionContainer,
      visitedFragmentNames: MutableSet[String] = MutableSet(),
      astAndDefs: CollectedFields = MutableMap()): CollectedFields = {
    var aad = astAndDefs

    selCont.selections foreach {
      case astField: ast.Field ⇒
        val fieldDef = parentType flatMap {
          case tpe: ObjectLikeType[Any @unchecked, Any @unchecked] ⇒ tpe.getField(ctx.schema, astField.name).headOption
          case _ ⇒ None
        }

        if (!aad.contains(astField.outputName))
          aad(astField.outputName) = ListBuffer((parentType, astField, fieldDef))
        else
          aad(astField.outputName) += ((parentType, astField, fieldDef))
      case frag: ast.InlineFragment ⇒
        aad = collectFieldASTsAndDefs(ctx, frag.typeCondition.fold(parentType)(ctx.schema.getOutputType(_, true)), frag, visitedFragmentNames, aad)
      case frag: ast.FragmentSpread if visitedFragmentNames contains frag.name ⇒
        // do nothing at all
      case frag: ast.FragmentSpread  ⇒
        visitedFragmentNames += frag.name

        ctx.fragments.get(frag.name) match {
          case Some(fragDef) ⇒
            aad = collectFieldASTsAndDefs(ctx, ctx.schema.getOutputType(fragDef.typeCondition, true), fragDef, visitedFragmentNames, aad)
          case None ⇒ // do nothing
        }
    }

    aad
  }
}

case class Conflict(reason: ConflictReason, fields: List[ast.Field])
case class ConflictReason(fieldName: String, reason: Either[String, Vector[ConflictReason]])

/**
 * A way to keep track of pairs of things when the ordering of the pair does
 * not matter. We do this by maintaining a sort of double adjacency sets.
 */
private class PairSet[T] {
  val pairs = MutableMap[T, MutableSet[T]]()

  def contains(a: T, b: T) =
    pairs.contains(a) && pairs(a).contains(b)

  def add(a: T, b: T) = {
    addPair(a, b)
    addPair(b, a)
  }

  private def addPair(a: T, b: T) =
    if (!pairs.contains(a))
      pairs(a) = MutableSet(b)
    else
      pairs(a).add(b)
}