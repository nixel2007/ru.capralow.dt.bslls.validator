package ru.capralow.dt.bslls.validator.plugin.internal.ui;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.EObjectAtOffsetHelper;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.util.CancelIndicator;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.resource.BslResource;
import com._1c.g5.v8.dt.bsl.validation.CustomValidationMessageAcceptor;
import com._1c.g5.v8.dt.bsl.validation.IExternalBslValidator;
import com.github._1c_syntax.bsl.languageserver.configuration.LanguageServerConfiguration;
import com.github._1c_syntax.bsl.languageserver.context.DocumentContext;
import com.github._1c_syntax.bsl.languageserver.context.ServerContext;
import com.github._1c_syntax.bsl.languageserver.diagnostics.BSLDiagnostic;
import com.github._1c_syntax.bsl.languageserver.diagnostics.FunctionShouldHaveReturnDiagnostic;
import com.github._1c_syntax.bsl.languageserver.diagnostics.LineLengthDiagnostic;
import com.github._1c_syntax.bsl.languageserver.diagnostics.ParseErrorDiagnostic;
import com.github._1c_syntax.bsl.languageserver.diagnostics.ProcedureReturnsValueDiagnostic;
import com.github._1c_syntax.bsl.languageserver.diagnostics.QuickFixProvider;
import com.github._1c_syntax.bsl.languageserver.diagnostics.UnknownPreprocessorSymbolDiagnostic;
import com.github._1c_syntax.bsl.languageserver.diagnostics.UnreachableCodeDiagnostic;
import com.github._1c_syntax.bsl.languageserver.diagnostics.UsingServiceTagDiagnostic;
import com.github._1c_syntax.bsl.languageserver.diagnostics.metadata.DiagnosticType;
import com.github._1c_syntax.bsl.languageserver.providers.DiagnosticProvider;

public class BslValidator implements IExternalBslValidator {
	private static final String QUICKFIX_CODE = "bsl-language-server"; //$NON-NLS-1$
	private static final String BSL_LS_PREFIX = "[BSL LS] "; //$NON-NLS-1$

	private static final Either<Boolean, Map<String, Object>> falseForLeft = Either.forLeft(false);

	private static IPath getConfigurationFilePath() {
		Bundle bundle = Platform.getBundle(BslValidatorPlugin.ID);
		return Platform.getStateLocation(bundle);
	}

	private static Integer[] getOffsetAndLength(Range range, Document doc) {
		Integer offset = 0;
		Integer length = 0;
		try {
			offset = doc.getLineOffset(range.getStart().getLine()) + range.getStart().getCharacter();
			Integer endOffset = doc.getLineOffset(range.getEnd().getLine()) + range.getEnd().getCharacter();
			length = endOffset - offset;

		} catch (BadLocationException e) {
			BslValidatorPlugin
					.log(BslValidatorPlugin.createErrorStatus(Messages.BslValidator_Bad_Location_Exception, e));

		}

		Integer[] result = new Integer[2];
		result[0] = offset;
		result[1] = length;

		return result;
	}

	private DiagnosticProvider diagnosticProvider;

	private Map<Class<? extends BSLDiagnostic>, QuickFixProvider> quickFixProviders;

	private ServerContext bslServerContext;

	private EObjectAtOffsetHelper eObjectOffsetHelper;

	public BslValidator() {
		super();

		File configurationFile = new File(getConfigurationFilePath() + File.separator + ".bsl-language-server.json"); //$NON-NLS-1$
		LanguageServerConfiguration configuration = LanguageServerConfiguration.create(configurationFile);

		Map<String, Either<Boolean, Map<String, Object>>> diagnostics = configuration.getDiagnostics();
		if (diagnostics == null)
			diagnostics = new HashMap<>();

		Collection<Class<? extends BSLDiagnostic>> duplicateDiagnostics = new ArrayList<>();

		// Свои механизмы в EDT
		duplicateDiagnostics.add(LineLengthDiagnostic.class);
		duplicateDiagnostics.add(ParseErrorDiagnostic.class);
		duplicateDiagnostics.add(UsingServiceTagDiagnostic.class);

		// Диагностики есть в EDT
		duplicateDiagnostics.add(FunctionShouldHaveReturnDiagnostic.class);
		duplicateDiagnostics.add(ProcedureReturnsValueDiagnostic.class);
		duplicateDiagnostics.add(UnknownPreprocessorSymbolDiagnostic.class);
		duplicateDiagnostics.add(UnreachableCodeDiagnostic.class);

		// В настройках можно принудительно включить выключенные диагностики
		for (Class<? extends BSLDiagnostic> diagnostic : duplicateDiagnostics) {
			String diagnocticCode = DiagnosticProvider.getDiagnosticCode(diagnostic);
			if (!diagnostics.containsKey(diagnocticCode))
				diagnostics.put(diagnocticCode, falseForLeft);
		}

		configuration.setDiagnostics(diagnostics);

		diagnosticProvider = new DiagnosticProvider(configuration);
		quickFixProviders = new HashMap<>();
		bslServerContext = new ServerContext();
		eObjectOffsetHelper = new EObjectAtOffsetHelper();
	}

	@Override
	public boolean needValidation(EObject object) {
		if (!(object instanceof Module))
			return false;

		boolean isDeepAnalysing = ((BslResource) ((Module) object).eResource()).isDeepAnalysing();

		if (!isDeepAnalysing)
			bslServerContext = new ServerContext();

		return isDeepAnalysing;
	}

	@Override
	public void validate(EObject object, CustomValidationMessageAcceptor messageAcceptor, CancelIndicator monitor) {
		validateModule(object, messageAcceptor, monitor);
	}

	private StringBuilder getIssueData(Diagnostic diagnostic, Class<? extends BSLDiagnostic> bslDiagnosticClass,
			DocumentContext documentContext, Document doc) {
		StringBuilder issueData = new StringBuilder();

		if (documentContext == null || !QuickFixProvider.class.isAssignableFrom(bslDiagnosticClass))
			return issueData;

		QuickFixProvider diagnosticInstance = quickFixProviders.computeIfAbsent(bslDiagnosticClass,
				k -> (QuickFixProvider) diagnosticProvider.getDiagnosticInstance(k));

		List<CodeAction> quickFixes = diagnosticInstance
				.getQuickFixes(Collections.singletonList(diagnostic), null, documentContext);

		for (CodeAction quickFix : quickFixes) {
			List<TextEdit> changes = quickFix.getEdit().getChanges().get(documentContext.getUri());
			if (changes.size() != 1)
				continue;

			TextEdit change = changes.get(0);
			Integer[] offsetAndLength = getOffsetAndLength(change.getRange(), doc);
			Integer offset = offsetAndLength[0];
			Integer length = offsetAndLength[1];

			List<String> issueLine = new ArrayList<>();
			issueLine.add(BSL_LS_PREFIX.concat(quickFix.getTitle()));
			issueLine.add(diagnostic.getMessage());
			issueLine.add(offset.toString());
			issueLine.add(length.toString());
			issueLine.add(change.getNewText());

			if (issueData.length() != 0)
				issueData.append(System.lineSeparator());
			issueData.append(String.join(",", issueLine)); //$NON-NLS-1$
		}

		return issueData;

	}

	private void registerIssue(EObject object, CustomValidationMessageAcceptor messageAcceptor, Diagnostic diagnostic,
			XtextResource eobjectResource, DocumentContext documentContext, Document doc) {
		Class<? extends BSLDiagnostic> bslDiagnosticClass = DiagnosticProvider.getBSLDiagnosticClass(diagnostic);

		Integer[] offsetAndLength = getOffsetAndLength(diagnostic.getRange(), doc);
		Integer offset = offsetAndLength[0];
		Integer length = offsetAndLength[1];

		EObject diagnosticObject = eObjectOffsetHelper.resolveContainedElementAt(eobjectResource, offset);
		if (diagnosticObject == null)
			diagnosticObject = object;

		StringBuilder issueData = getIssueData(diagnostic, bslDiagnosticClass, documentContext, doc);

		String diagnosticMessage = BSL_LS_PREFIX.concat(diagnostic.getMessage());

		DiagnosticType diagnosticType = DiagnosticProvider.getDiagnosticType(bslDiagnosticClass);
		if (diagnosticType.equals(DiagnosticType.ERROR) || diagnosticType.equals(DiagnosticType.VULNERABILITY))
			messageAcceptor.acceptError(diagnosticMessage,
					diagnosticObject,
					offset,
					length,
					QUICKFIX_CODE,
					issueData.toString());

		else
			messageAcceptor.acceptWarning(diagnosticMessage,
					diagnosticObject,
					offset,
					length,
					QUICKFIX_CODE,
					issueData.toString());

	}

	private void validateModule(EObject object, CustomValidationMessageAcceptor messageAcceptor,
			CancelIndicator monitor) {
		if (monitor.isCanceled())
			return;

		XtextResource eObjectResource = (XtextResource) object.eResource();

		Module module = (Module) object;
		ICompositeNode node = NodeModelUtils.findActualNodeFor(module);
		String objectUri = module.getUniqueName();
		String objectText = node.getText();

		Document doc = new Document(objectText);

		long startTime = System.currentTimeMillis();
		BslValidatorPlugin
				.log(BslValidatorPlugin.createInfoStatus(BSL_LS_PREFIX.concat("Начало передачи текста модуля"))); //$NON-NLS-1$
		DocumentContext documentContext = bslServerContext.addDocument(objectUri, objectText);
		long endTime = System.currentTimeMillis();
		String difference = " (".concat(Long.toString((endTime - startTime) / 1000)).concat("s)"); //$NON-NLS-1$ //$NON-NLS-2$
		BslValidatorPlugin.log(BslValidatorPlugin
				.createInfoStatus(BSL_LS_PREFIX.concat("Окончание передачи текста модуля").concat(difference))); //$NON-NLS-1$

		List<Diagnostic> diagnostics = diagnosticProvider.computeDiagnostics(documentContext);
		for (Diagnostic diagnostic : diagnostics)
			registerIssue(object, messageAcceptor, diagnostic, eObjectResource, documentContext, doc);
	}

}
