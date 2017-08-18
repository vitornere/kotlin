/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.intentions

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemHighlightType.GENERIC_ERROR_OR_WARNING
import com.intellij.codeInspection.ProblemHighlightType.INFORMATION
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.core.canOmitDeclaredType
import org.jetbrains.kotlin.idea.core.replaced
import org.jetbrains.kotlin.idea.core.setType
import org.jetbrains.kotlin.idea.inspections.IntentionBasedInspection
import org.jetbrains.kotlin.idea.inspections.findExistingEditor
import org.jetbrains.kotlin.idea.intentions.branchedTransformations.isOneLiner
import org.jetbrains.kotlin.idea.util.CommentSaver
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.anyDescendantOfType
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.jetbrains.kotlin.resolve.BindingContext

private fun KtDeclarationWithBody.blockExpression() = when (this) {
    is KtFunctionLiteral -> null
    else -> {
        val body = bodyExpression
        if (!hasBlockBody() || body !is KtBlockExpression) null else body
    }
}

private fun KtDeclarationWithBody.findValueStatement(convertEmptyToUnit: Boolean): KtExpression? {
    val body = blockExpression() ?: return null
    return body.findValueStatement(convertEmptyToUnit)
}

private fun KtBlockExpression.findValueStatement(convertEmptyToUnit: Boolean): KtExpression? {
    val bodyStatements = statements
    if (bodyStatements.isEmpty()) {
        return if (convertEmptyToUnit) KtPsiFactory(this).createExpression("Unit") else null
    }
    val statement = bodyStatements.singleOrNull() ?: return null
    when (statement) {
        is KtReturnExpression -> {
            return statement
        }

        //TODO: IMO this is not good code, there should be a way to detect that KtExpression does not have value
        is KtDeclaration, is KtLoopExpression -> return null

        else -> {
            // assignment does not have value
            if (statement is KtBinaryExpression && statement.operationToken in KtTokens.ALL_ASSIGNMENTS) return null

            val context = statement.analyze()
            val expressionType = context.getType(statement) ?: return null
            val isUnit = KotlinBuiltIns.isUnit(expressionType)
            if (!isUnit && !KotlinBuiltIns.isNothing(expressionType)) return null
            if (isUnit) {
                if (statement.hasResultingIfWithoutElse()) {
                    return null
                }
                val resultingWhens = statement.resultingWhens()
                if (resultingWhens.any { it.elseExpression == null && context.get(BindingContext.EXHAUSTIVE_WHEN, it) != true }) {
                    return null
                }
            }
            return statement
        }
    }
}

private fun KtExpression.getValue() = when (this) {
                                          is KtReturnExpression -> returnedExpression
                                          else -> null
                                      } ?: this

class UseExpressionBodyInspection(private val convertEmptyToUnit: Boolean) : IntentionBasedInspection<KtDeclarationWithBody>(
        intention = ConvertToExpressionBodyIntention::class,
        additionalChecker = { declaration, inspection ->
            (inspection as UseExpressionBodyInspection).isActiveFor(declaration)
        }
) {
    constructor() : this(convertEmptyToUnit = true)

    private data class Status(val toHighlight: PsiElement?, val subject: String, val highlightType: ProblemHighlightType)

    fun isActiveFor(declaration: KtDeclarationWithBody) = statusFor(declaration)?.highlightType == GENERIC_ERROR_OR_WARNING

    private fun statusFor(declaration: KtDeclarationWithBody): Status? {
        if (declaration is KtConstructor<*>) return null

        val valueStatement = declaration.findValueStatement(convertEmptyToUnit) ?: return null
        val value = valueStatement.getValue()
        if (value.anyDescendantOfType<KtReturnExpression>(
                canGoInside = { it !is KtFunctionLiteral && it !is KtNamedFunction && it !is KtPropertyAccessor }
        )) return null

        val toHighlight = valueStatement.toHighlight()
        return when {
            valueStatement !is KtReturnExpression -> Status(toHighlight, "block body", INFORMATION)
            valueStatement.returnedExpression is KtWhenExpression -> Status(toHighlight, "'return when'", GENERIC_ERROR_OR_WARNING)
            valueStatement.isOneLiner() -> Status(toHighlight, "one-line return", GENERIC_ERROR_OR_WARNING)
            else -> Status(toHighlight, "return", INFORMATION)
        }
    }

    private fun KtExpression.toHighlight(): PsiElement? = when (this) {
        is KtReturnExpression -> returnKeyword
        is KtCallExpression -> calleeExpression
        is KtQualifiedExpression -> selectorExpression?.toHighlight()
        is KtObjectLiteralExpression -> objectDeclaration.getObjectKeyword()
        else -> null
    }

    private val intention = ConvertToExpressionBodyIntention(convertEmptyToUnit)

    fun simplify(declaration: KtDeclarationWithBody, canDeleteTypeRef: Boolean) {
        intention.simplify(declaration, canDeleteTypeRef)
    }
}

class ConvertToExpressionBodyIntention(
        private val convertEmptyToUnit: Boolean
) : SelfTargetingOffsetIndependentIntention<KtDeclarationWithBody>(
        KtDeclarationWithBody::class.java, "Convert to expression body"
) {
    @Suppress("unused")
    constructor() : this(convertEmptyToUnit = true)

    override fun isApplicableTo(element: KtDeclarationWithBody): Boolean {
        if (element is KtConstructor<*>) return false

        val valueStatement = element.findValueStatement(convertEmptyToUnit) ?: return false
        val value = valueStatement.getValue()
        return !value.anyDescendantOfType<KtReturnExpression>(
                canGoInside = { it !is KtFunctionLiteral && it !is KtNamedFunction && it !is KtPropertyAccessor }
        )
    }

    override fun allowCaretInsideElement(element: PsiElement) = element !is KtDeclaration

    fun simplify(declaration: KtDeclarationWithBody, canDeleteTypeRef: Boolean) {
        val deleteTypeHandler: (KtCallableDeclaration) -> Unit = {
            it.deleteChildRange(it.colon!!, it.typeReference!!)
        }
        simplify(declaration, deleteTypeHandler.takeIf { canDeleteTypeRef })
    }

    private fun simplify(declaration: KtDeclarationWithBody, deleteTypeHandler: ((KtCallableDeclaration) -> Unit)?) {
        val block = declaration.blockExpression() ?: return
        val valueStatement = block.findValueStatement(convertEmptyToUnit) ?: return
        val value = valueStatement.getValue()

        if (!declaration.hasDeclaredReturnType() && declaration is KtNamedFunction && block.statements.isNotEmpty()) {
            val valueType = value.analyze().getType(value)
            if (valueType == null || !KotlinBuiltIns.isUnit(valueType)) {
                declaration.setType(KotlinBuiltIns.FQ_NAMES.unit.asString(), shortenReferences = true)
            }
        }

        val body = declaration.bodyExpression!!

        val commentSaver = CommentSaver(body)

        val factory = KtPsiFactory(declaration)
        declaration.addBefore(factory.createEQ(), body)
        val newBody = body.replaced(value)

        commentSaver.restore(newBody)

        if (deleteTypeHandler != null && declaration is KtCallableDeclaration) {
            if (declaration.hasDeclaredReturnType() && declaration.canOmitDeclaredType(newBody, canChangeTypeToSubtype = true)) {
                deleteTypeHandler(declaration)
            }
        }

        val editor = declaration.findExistingEditor()
        if (editor != null) {
            val startOffset = newBody.startOffset
            val document = editor.document
            val startLine = document.getLineNumber(startOffset)
            val rightMargin = editor.settings.getRightMargin(editor.project)
            if (document.getLineEndOffset(startLine) - document.getLineStartOffset(startLine) >= rightMargin) {
                declaration.addBefore(factory.createNewLine(), newBody)
            }
        }
    }

    override fun applyTo(element: KtDeclarationWithBody, editor: Editor?) {
        simplify(element) {
            val typeRef = it.typeReference!!
            val colon = it.colon!!
            editor?.apply {
                selectionModel.setSelection(colon.startOffset, typeRef.endOffset)
                caretModel.moveToOffset(typeRef.endOffset)
            }
        }
    }
}
