/*
 * Copyright 2006-2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gametube.redisflexiblecache.ast

import org.codehaus.groovy.ast.*
import org.codehaus.groovy.ast.builder.AstBuilder
import org.codehaus.groovy.ast.expr.*
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ReturnStatement
import org.codehaus.groovy.ast.stmt.Statement
import org.codehaus.groovy.classgen.VariableScopeVisitor
import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.messages.SyntaxErrorMessage
import org.codehaus.groovy.syntax.SyntaxException
import org.codehaus.groovy.transform.ASTTransformation
import org.codehaus.groovy.transform.GroovyASTTransformation
import org.gametube.redisflexiblecache.RedisFlexibleCacheService

import static org.springframework.asm.Opcodes.ACC_PRIVATE
import static org.springframework.asm.Opcodes.ACC_PUBLIC

/**
 * Abstract AST transformation for annotations provided by the plugin
 */
@GroovyASTTransformation(phase = CompilePhase.CANONICALIZATION)
abstract class AbstractRedisFlexibleASTTransformation implements ASTTransformation {

    protected static final String KEY = 'key'
    protected static final String EXPIRE = 'expire'
    protected static final String GROUP = 'group'
    protected static final String REATTACH_TO_SESSION = 'reAttachToSession'
    protected static final String HASH_CODE = '#'
    protected static final String GSTRING = '$'
    protected static final String CACHE_SERVICE = 'redisFlexibleCacheService'

    void visit(ASTNode[] astNodes, SourceUnit sourceUnit) {
        //map to hold the params we will pass to the doCache method
        def doCacheProperties = [:]

        try {
            injectCacheService(sourceUnit)
            generateDoCacheProperties(astNodes, sourceUnit, doCacheProperties)
            // if the key is missing there is an issue with the annotation
            if (!doCacheProperties.containsKey(KEY) || !doCacheProperties.get(KEY)) {
                return
            }
            addDoCachedStatements((MethodNode) astNodes[1], doCacheProperties)
            visitVariableScopes(sourceUnit)
        } catch (Exception e) {
            addError("Error during RedisFlexibleCache AST Transformation: ${e}", astNodes[0], sourceUnit)
            throw e
        }
    }

    /**
     * Create the statements for the doCache method, clear the node and then readd the doCache code back to the
     * method.
     * @param methodNode the methodNode we will be cleared and replaced with the cacheService.doCache method call.
     * @param doCacheProperties the map of properties to use for the service invocation
     */
    private void addDoCachedStatements(MethodNode methodNode, LinkedHashMap doCacheProperties) {
        def stmt = doCacheMethod(methodNode, doCacheProperties)
        methodNode.code.statements.clear()
        methodNode.code.statements.addAll(stmt)
    }

    /**
     * Fix the variable scopes for closures.  Without this, closures will be missing the input params being passed from
     * the parent scope.
     * @param sourceUnit The SourceUnit to visit and add the variable scopes.
     */
    private void visitVariableScopes(SourceUnit sourceUnit) {
        VariableScopeVisitor scopeVisitor = new VariableScopeVisitor(sourceUnit);
        sourceUnit.AST.classes.each {
            scopeVisitor.visitClass(it)
        }
    }

    /**
     * Determine whether the user missed injecting the cacheService into the class with the @RedisFlexibleCache method.
     * @param sourceUnit SourceUnit to detect and/or inject service into
     */
    private void injectCacheService(SourceUnit sourceUnit) {
        if (!((ClassNode) sourceUnit.AST.classes.toArray()[0]).properties?.any { it?.field?.name == CACHE_SERVICE }) {
            if (!sourceUnit.AST.imports.any { it.className == ClassHelper.make(RedisFlexibleCacheService).name }
                    && !sourceUnit.AST.starImports.any { it.packageName == "${ClassHelper.make(RedisFlexibleCacheService).packageName}." }) {
                sourceUnit.AST.addImport('RedisFlexibleCacheService', ClassHelper.make(RedisFlexibleCacheService))
            }
            addProperty((ClassNode) sourceUnit.AST.classes.toArray()[0], CACHE_SERVICE)
        }
    }

    /**
     * Add a new property to the class. Groovy automatically handles adding the getters and setters so you
     * don't have to create special methods for those. This could be reused for other properties.
     * @param cNode Node to inject property onto.  Usually a ClassNode for the current class.
     * @param propertyName The name of the property to inject.
     * @param propertyType The object class of the property. (defaults to Object.class)
     * @param initialValue Initial value of the property. (defaults null)
     */
    private void addProperty(ClassNode cNode, String propertyName, Class propertyType = java.lang.Object.class, Expression initialValue = null) {
        FieldNode field = new FieldNode(
                propertyName,
                ACC_PRIVATE,
                new ClassNode(propertyType),
                new ClassNode(cNode.class),
                initialValue
        )

        cNode.addProperty(new PropertyNode(field, ACC_PUBLIC, null, null))
    }

    /**
     * Add the key and expires and options if they exist
     * @param astNodes the ast nodes
     * @param sourceUnit the source unit
     * @param doCacheProperties map to put data in
     * @return
     */
    protected abstract void generateDoCacheProperties(ASTNode[] astNodes, SourceUnit sourceUnit, Map doCacheProperties)

    protected abstract ConstantExpression makeCacheServiceConstantExpression()

    protected abstract ArgumentListExpression makeRedisServiceArgumentListExpression(Map doCacheProperties)

    protected List<Statement> doCacheMethod(MethodNode methodNode, Map doCacheProperties) {
        BlockStatement body = new BlockStatement()
        addRedisServiceDoCacheInvocation(body, methodNode, doCacheProperties)
        body.statements
    }

    protected void addRedisServiceDoCacheInvocation(BlockStatement body, MethodNode methodNode, Map doCacheProperties) {
        ArgumentListExpression argumentListExpression = makeRedisServiceArgumentListExpression(doCacheProperties)
        argumentListExpression.addExpression(makeClosureExpression(methodNode))

        body.addStatement(
                new ReturnStatement(
                        new MethodCallExpression(
                                new VariableExpression(CACHE_SERVICE),
                                makeCacheServiceConstantExpression(),
                                argumentListExpression
                        )
                )
        )
    }

    protected void addRedisServiceDoCacheKeyExpression(Map doCacheProperties, ArgumentListExpression argumentListExpression) {
        if (doCacheProperties.get(KEY).toString().contains(HASH_CODE)) {
            def ast = new AstBuilder().buildFromString("""
                "${doCacheProperties.get(KEY).toString().replace(HASH_CODE, GSTRING).toString()}"
           """)
            argumentListExpression.addExpression(ast[0].statements[0].expression)
        } else {
            argumentListExpression.addExpression(new ConstantExpression(doCacheProperties.get(KEY).toString()))
        }
    }

    protected ClosureExpression makeClosureExpression(MethodNode methodNode) {
        ClosureExpression closureExpression = new ClosureExpression(
                [] as Parameter[],
                new BlockStatement(methodNode.code.statements as Statement[], new VariableScope())
        )
        closureExpression.variableScope = new VariableScope()
        closureExpression
    }

    protected ConstantExpression makeConstantExpression(constantExpression) {
        new ConstantExpression(constantExpression)
    }

    protected void addError(String msg, ASTNode node, SourceUnit source) {
        int line = node.lineNumber
        int col = node.columnNumber
        SyntaxException se = new SyntaxException("${msg}\n", line, col)
        SyntaxErrorMessage sem = new SyntaxErrorMessage(se, source)
        source.errorCollector.addErrorAndContinue(sem)
    }
}
