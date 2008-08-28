/*******************************************************************************
 * Copyright (c) 2000, 2008 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.core.tests.model;

import java.io.*;
import java.net.URL;
import java.util.*;

import junit.framework.Test;
import junit.framework.TestSuite;

import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.*;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.*;
import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.search.*;
import org.eclipse.jdt.core.tests.junit.extension.TestCase;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.core.ClasspathEntry;
import org.eclipse.jdt.internal.core.JavaCorePreferenceInitializer;
import org.eclipse.jdt.internal.core.JavaElement;
import org.eclipse.jdt.internal.core.JavaModelManager;
import org.eclipse.jdt.internal.core.JavaProject;
import org.eclipse.jdt.internal.core.NameLookup;
import org.eclipse.jdt.internal.core.ResolvedSourceMethod;
import org.eclipse.jdt.internal.core.ResolvedSourceType;
import org.eclipse.jdt.internal.core.search.BasicSearchEngine;
import org.eclipse.jdt.internal.core.util.Util;

public abstract class AbstractJavaModelTests extends SuiteOfTestCases {

	/**
	 * The java.io.File path to the directory that contains the external jars.
	 */
	protected static String EXTERNAL_JAR_DIR_PATH;

	// used java project
	protected IJavaProject currentProject;

	// working copies usage
	protected ICompilationUnit[] workingCopies;
	protected WorkingCopyOwner wcOwner;

	// infos for invalid results
	protected int tabs = 2;
	protected boolean displayName = false;
	protected String endChar = ",";

	public static class BasicProblemRequestor implements IProblemRequestor {
		public void acceptProblem(IProblem problem) {}
		public void beginReporting() {}
		public void endReporting() {}
		public boolean isActive() {
			return true;
		}
	}

	public static class ProblemRequestor implements IProblemRequestor {
		public StringBuffer problems;
		public int problemCount;
		protected char[] unitSource;
		public boolean isActive = true;
		public ProblemRequestor() {
			initialize(null);
		}
		public void acceptProblem(IProblem problem) {
			org.eclipse.jdt.core.tests.util.Util.appendProblem(this.problems, problem, this.unitSource, ++this.problemCount);
			this.problems.append("----------\n");
		}
		public void beginReporting() {
			this.problems.append("----------\n");
		}
		public void endReporting() {
			if (this.problemCount == 0)
				this.problems.append("----------\n");
		}
		public boolean isActive() {
			return this.isActive;
		}
		public void initialize(char[] source) {
			reset();
			this.unitSource = source;
		}
		public void reset() {
			this.problems = new StringBuffer();
			this.problemCount = 0;
		}
	}

	/**
	 * Delta listener
	 */
	protected class DeltaListener implements IElementChangedListener {
		/**
		 * Deltas received from the java model. See
		 * <code>#startDeltas</code> and
		 * <code>#stopDeltas</code>.
		 */
		public IJavaElementDelta[] deltas;

		int eventType;

		public ByteArrayOutputStream stackTraces;

		public DeltaListener() {
			flush();
			this.eventType = -1;
		}
		public DeltaListener(int eventType) {
			flush();
			this.eventType = eventType;
		}

		public void elementChanged(ElementChangedEvent event) {
			if (this.eventType == -1 || event.getType() == this.eventType) {
				IJavaElementDelta[] copy= new IJavaElementDelta[this.deltas.length + 1];
				System.arraycopy(this.deltas, 0, copy, 0, this.deltas.length);
				copy[this.deltas.length]= event.getDelta();
				this.deltas= copy;
	
				new Throwable("Caller of IElementChangedListener#elementChanged").printStackTrace(new PrintStream(this.stackTraces));
			}
		}
		public CompilationUnit getCompilationUnitAST(ICompilationUnit workingCopy) {
			for (int i=0, length= this.deltas.length; i<length; i++) {
				CompilationUnit result = getCompilationUnitAST(workingCopy, this.deltas[i]);
				if (result != null)
					return result;
			}
			return null;
		}
		private CompilationUnit getCompilationUnitAST(ICompilationUnit workingCopy, IJavaElementDelta delta) {
			if ((delta.getFlags() & IJavaElementDelta.F_AST_AFFECTED) != 0 && workingCopy.equals(delta.getElement()))
				return delta.getCompilationUnitAST();
			return null;
		}
		public void flush() {
			this.deltas = new IJavaElementDelta[0];
			this.stackTraces = new ByteArrayOutputStream();
		}
		protected void sortDeltas(IJavaElementDelta[] elementDeltas) {
        	org.eclipse.jdt.internal.core.util.Util.Comparer comparer = new org.eclipse.jdt.internal.core.util.Util.Comparer() {
        		public int compare(Object a, Object b) {
        			IJavaElementDelta deltaA = (IJavaElementDelta)a;
        			IJavaElementDelta deltaB = (IJavaElementDelta)b;
        			return toString(deltaA).compareTo(toString(deltaB));
        		}
        		private String toString(IJavaElementDelta delta) {
        			if (delta.getElement().getElementType() == IJavaElement.PACKAGE_FRAGMENT_ROOT) {
        				return delta.getElement().getPath().setDevice(null).toString();
        			}
        			return delta.toString();
        		}
        	};
        	org.eclipse.jdt.internal.core.util.Util.sort(elementDeltas, comparer);
        	for (int i = 0, max = elementDeltas.length; i < max; i++) {
        		IJavaElementDelta delta = elementDeltas[i];
        		IJavaElementDelta[] children = delta.getAffectedChildren();
        		if (children != null) {
        			sortDeltas(children);
        		}
        	}
        }
		public String toString() {
			StringBuffer buffer = new StringBuffer();
			for (int i=0, length= this.deltas.length; i<length; i++) {
				IJavaElementDelta delta = this.deltas[i];
				IJavaElementDelta[] children = delta.getAffectedChildren();
				int childrenLength=children.length;
				IResourceDelta[] resourceDeltas = delta.getResourceDeltas();
				int resourceDeltasLength = resourceDeltas == null ? 0 : resourceDeltas.length;
				if (childrenLength == 0 && resourceDeltasLength == 0) {
					buffer.append(delta);
				} else {
					sortDeltas(children);
					for (int j=0; j<childrenLength; j++) {
						buffer.append(children[j]);
						if (j != childrenLength-1) {
							buffer.append("\n");
						}
					}
					for (int j=0; j<resourceDeltasLength; j++) {
						if (j == 0 && buffer.length() != 0) {
							buffer.append("\n");
						}
						buffer.append(resourceDeltas[j]);
						if (j != resourceDeltasLength-1) {
							buffer.append("\n");
						}
					}
				}
				if (i != length-1) {
					buffer.append("\n\n");
				}
			}
			return buffer.toString();
		}
	}
	protected DeltaListener deltaListener = new DeltaListener();

	protected ILogListener logListener;


	public AbstractJavaModelTests(String name) {
		super(name);
	}

	public AbstractJavaModelTests(String name, int tabs) {
		super(name);
		this.tabs = tabs;
	}

	/**
	 * Build a test suite with all tests computed from public methods starting with "test"
	 * found in the given test class.
	 * Test suite name is the name of the given test class.
	 *
	 * Note that this lis maybe reduced using some mechanisms detailed in {@link #buildTestsList(Class)} method.
	 *
	 * This test suite differ from this computed in {@link TestCase} in the fact that this is
	 * a {@link SuiteOfTestCases.Suite} instead of a simple framework {@link TestSuite}.
	 *
	 * @param evaluationTestClass
	 * @return a test suite ({@link Test})
	 */
	public static Test buildModelTestSuite(Class evaluationTestClass) {
		return buildModelTestSuite(evaluationTestClass, ORDERING);
	}

	/**
	 * Build a test suite with all tests computed from public methods starting with "test"
	 * found in the given test class and sorted in alphabetical order.
	 * Test suite name is the name of the given test class.
	 *
	 * Note that this lis maybe reduced using some mechanisms detailed in {@link #buildTestsList(Class)} method.
	 *
	 * This test suite differ from this computed in {@link TestCase} in the fact that this is
	 * a {@link SuiteOfTestCases.Suite} instead of a simple framework {@link TestSuite}.
	 *
	 * @param evaluationTestClass
	 * @param ordering kind of sort use for the list (see {@link #ORDERING} for possible values)
	 * @return a test suite ({@link Test})
	 */
	public static Test buildModelTestSuite(Class evaluationTestClass, long ordering) {
		TestSuite suite = new Suite(evaluationTestClass.getName());
		List tests = buildTestsList(evaluationTestClass, 0, ordering);
		for (int index=0, size=tests.size(); index<size; index++) {
			suite.addTest((Test)tests.get(index));
		}
		return suite;
	}

	protected void addJavaNature(String projectName) throws CoreException {
		IProject project = getWorkspaceRoot().getProject(projectName);
		IProjectDescription description = project.getDescription();
		description.setNatureIds(new String[] {JavaCore.NATURE_ID});
		project.setDescription(description, null);
	}
	protected void assertSearchResults(String expected, Object collector) {
		assertSearchResults("Unexpected search results", expected, collector);
	}
	protected void assertSearchResults(String message, String expected, Object collector) {
		String actual = collector.toString();
		if (!expected.equals(actual)) {
			if (this.displayName) System.out.println(getName()+" actual result is:");
			System.out.print(displayString(actual, this.tabs));
			System.out.println(",");
		}
		assertEquals(
			message,
			expected,
			actual
		);
	}
	protected void assertScopeEquals(String expected, IJavaSearchScope scope) {
		String actual = scope.toString();
		if (!expected.equals(actual)) {
			System.out.println(displayString(actual, 3) + ",");
		}
		assertEquals("Unexpected scope", expected, actual);
	}
	protected void addClasspathEntry(IJavaProject project, IClasspathEntry entry) throws JavaModelException{
		IClasspathEntry[] entries = project.getRawClasspath();
		int length = entries.length;
		System.arraycopy(entries, 0, entries = new IClasspathEntry[length + 1], 0, length);
		entries[length] = entry;
		project.setRawClasspath(entries, null);
	}
	protected void addClassFolder(IJavaProject javaProject, String folderRelativePath, String[] pathAndContents, String compliance) throws CoreException, IOException {
		IProject project = javaProject.getProject();
		String projectLocation = project.getLocation().toOSString();
		String folderPath = projectLocation + File.separator + folderRelativePath;
    	org.eclipse.jdt.core.tests.util.Util.createClassFolder(pathAndContents, folderPath, compliance);
    	project.refreshLocal(IResource.DEPTH_INFINITE, null);
		String projectPath = '/' + project.getName() + '/';
		addLibraryEntry(
			javaProject,
			new Path(projectPath + folderRelativePath),
			null,
			null,
			null,
			null,
			true
		);

	}
	protected void addLibrary(String jarName, String sourceZipName, String[] pathAndContents, String compliance) throws CoreException, IOException {
		addLibrary(this.currentProject, jarName, sourceZipName, pathAndContents, null, null, compliance);
	}
	protected void addLibrary(IJavaProject javaProject, String jarName, String sourceZipName, String[] pathAndContents, String compliance) throws CoreException, IOException {
		addLibrary(javaProject, jarName, sourceZipName, pathAndContents, null, null, compliance);
	}
	protected void addLibrary(IJavaProject javaProject, String jarName, String sourceZipName, String[] pathAndContents, String[] librariesInclusionPatterns, String[] librariesExclusionPatterns, String compliance) throws CoreException, IOException {
		IProject project = javaProject.getProject();
		String projectLocation = project.getLocation().toOSString();
		String jarPath = projectLocation + File.separator + jarName;
		String sourceZipPath = projectLocation + File.separator + sourceZipName;
		org.eclipse.jdt.core.tests.util.Util.createJar(pathAndContents, jarPath, compliance);
		org.eclipse.jdt.core.tests.util.Util.createSourceZip(pathAndContents, sourceZipPath);
		project.refreshLocal(IResource.DEPTH_INFINITE, null);
		String projectPath = '/' + project.getName() + '/';
		addLibraryEntry(
			javaProject,
			new Path(projectPath + jarName),
			new Path(projectPath + sourceZipName),
			null,
			toIPathArray(librariesInclusionPatterns),
			toIPathArray(librariesExclusionPatterns),
			true
		);
	}
	protected void addLibraryEntry(String path, boolean exported) throws JavaModelException {
		addLibraryEntry(this.currentProject, new Path(path), null, null, null, null, exported);
	}
	protected void addLibraryEntry(IJavaProject project, String path, boolean exported) throws JavaModelException {
		addLibraryEntry(project, new Path(path), exported);
	}
	protected void addLibraryEntry(IJavaProject project, IPath path, boolean exported) throws JavaModelException {
		addLibraryEntry(project, path, null, null, null, null, exported);
	}
	protected void addLibraryEntry(IJavaProject project, String path, String srcAttachmentPath) throws JavaModelException{
		addLibraryEntry(
			project,
			new Path(path),
			srcAttachmentPath == null ? null : new Path(srcAttachmentPath),
			null,
			null,
			null,
			new IClasspathAttribute[0],
			false
		);
	}
	protected void addLibraryEntry(IJavaProject project, IPath path, IPath srcAttachmentPath, IPath srcAttachmentPathRoot, IPath[] accessibleFiles, IPath[] nonAccessibleFiles, boolean exported) throws JavaModelException{
		addLibraryEntry(
			project,
			path,
			srcAttachmentPath,
			srcAttachmentPathRoot,
			accessibleFiles,
			nonAccessibleFiles,
			new IClasspathAttribute[0],
			exported
		);
	}
	protected void addLibraryEntry(IJavaProject project, IPath path, IPath srcAttachmentPath, IPath srcAttachmentPathRoot, IPath[] accessibleFiles, IPath[] nonAccessibleFiles, IClasspathAttribute[] extraAttributes, boolean exported) throws JavaModelException{
		IClasspathEntry entry = JavaCore.newLibraryEntry(
			path,
			srcAttachmentPath,
			srcAttachmentPathRoot,
			ClasspathEntry.getAccessRules(accessibleFiles, nonAccessibleFiles),
			extraAttributes,
			exported);
		addClasspathEntry(project, entry);
	}
	protected void assertSortedElementsEqual(String message, String expected, IJavaElement[] elements) {
		sortElements(elements);
		assertElementsEqual(message, expected, elements);
	}

	protected void assertResourceEquals(String message, String expected, IResource resource) {
		String actual = resource == null ? "<null>" : resource.getFullPath().toString();
		if (!expected.equals(actual)) {
			System.out.print(org.eclipse.jdt.core.tests.util.Util.displayString(actual));
			System.out.println(this.endChar);
		}
		assertEquals(message, expected, actual);
	}

	protected void assertResourcesEqual(String message, String expected, Object[] resources) {
		sortResources(resources);
		StringBuffer buffer = new StringBuffer();
		for (int i = 0, length = resources.length; i < length; i++) {
			if (resources[i] instanceof IResource) {
				buffer.append(((IResource) resources[i]).getFullPath().toString());
			} else if (resources[i] instanceof IStorage) {
				buffer.append(((IStorage) resources[i]).getFullPath().toString());
			} else if (resources[i] == null) {
				buffer.append("<null>");
			}
			if (i != length-1)buffer.append("\n");
		}
		if (!expected.equals(buffer.toString())) {
			System.out.print(org.eclipse.jdt.core.tests.util.Util.displayString(buffer.toString(), 2));
			System.out.println(this.endChar);
		}
		assertEquals(
			message,
			expected,
			buffer.toString()
		);
	}

	protected void assertResourceNamesEqual(String message, String expected, Object[] resources) {
		sortResources(resources);
		StringBuffer buffer = new StringBuffer();
		for (int i = 0, length = resources.length; i < length; i++) {
			if (resources[i] instanceof IResource) {
				buffer.append(((IResource)resources[i]).getName());
			} else if (resources[i] instanceof IStorage) {
				buffer.append(((IStorage) resources[i]).getName());
			} else if (resources[i] == null) {
				buffer.append("<null>");
			}
			if (i != length-1)buffer.append("\n");
		}
		if (!expected.equals(buffer.toString())) {
			System.out.print(org.eclipse.jdt.core.tests.util.Util.displayString(buffer.toString(), 2));
			System.out.println(this.endChar);
		}
		assertEquals(
			message,
			expected,
			buffer.toString()
		);
	}

	protected void assertResourceTreeEquals(String message, String expected, Object[] resources) throws CoreException {
		sortResources(resources);
		StringBuffer buffer = new StringBuffer();
		for (int i = 0, length = resources.length; i < length; i++) {
			printResourceTree(resources[i], buffer, 0);
			if (i != length-1) buffer.append("\n");
		}
		if (!expected.equals(buffer.toString())) {
			System.out.print(org.eclipse.jdt.core.tests.util.Util.displayString(buffer.toString(), 2));
			System.out.println(this.endChar);
		}
		assertEquals(
			message,
			expected,
			buffer.toString()
		);
	}

	private void printResourceTree(Object resource, StringBuffer buffer, int indent) throws CoreException {
		for (int i = 0; i < indent; i++)
			buffer.append("  ");
		if (resource instanceof IResource) {
			buffer.append(((IResource) resource).getName());
			if (resource instanceof IContainer) {
				IResource[] children = ((IContainer) resource).members();
				int length = children.length;
				if (length > 0) buffer.append("\n");
				for (int j = 0; j < length; j++) {
					printResourceTree(children[j], buffer, indent+1);
					if (j != length-1) buffer.append("\n");
				}
			}
		} else if (resource instanceof IJarEntryResource) {
			IJarEntryResource jarEntryResource = (IJarEntryResource) resource;
			buffer.append(jarEntryResource.getName());
			if (!jarEntryResource.isFile()) {
				IJarEntryResource[] children = jarEntryResource.getChildren();
				int length = children.length;
				if (length > 0) buffer.append("\n");
				for (int j = 0; j < length; j++) {
					printResourceTree(children[j], buffer, indent+1);
					if (j != length-1) buffer.append("\n");
				}
			}
		} else if (resource == null) {
			buffer.append("<null>");
		}

	}

	protected void assertElementEquals(String message, String expected, IJavaElement element) {
		String actual = element == null ? "<null>" : ((JavaElement) element).toStringWithAncestors(false/*don't show key*/);
		if (!expected.equals(actual)) {
			if (this.displayName) System.out.println(getName()+" actual result is:");
			System.out.println(displayString(actual, this.tabs) + this.endChar);
		}
		assertEquals(message, expected, actual);
	}
	protected void assertElementsEqual(String message, String expected, IJavaElement[] elements) {
		assertElementsEqual(message, expected, elements, false/*don't show key*/);
	}
	protected void assertElementsEqual(String message, String expected, IJavaElement[] elements, boolean showResolvedInfo) {
		StringBuffer buffer = new StringBuffer();
		if (elements != null) {
			for (int i = 0, length = elements.length; i < length; i++){
				JavaElement element = (JavaElement)elements[i];
				if (element == null) {
					buffer.append("<null>");
				} else {
					buffer.append(element.toStringWithAncestors(showResolvedInfo));
				}
				if (i != length-1) buffer.append("\n");
			}
		} else {
			buffer.append("<null>");
		}
		String actual = buffer.toString();
		if (!expected.equals(actual)) {
			if (this.displayName) System.out.println(getName()+" actual result is:");
			System.out.println(displayString(actual, this.tabs) + this.endChar);
		}
		assertEquals(message, expected, actual);
	}
	protected void assertExceptionEquals(String message, String expected, Exception exception) {
		String actual =
			exception == null ?
				"<null>" :
				(exception instanceof CoreException) ?
					((CoreException) exception).getStatus().getMessage() :
					exception.toString();
		if (!expected.equals(actual)) {
			if (this.displayName) System.out.println(getName()+" actual result is:");
			System.out.println(displayString(actual, this.tabs) + this.endChar);
		}
		assertEquals(message, expected, actual);
	}
	protected void assertHierarchyEquals(String expected, ITypeHierarchy hierarchy) {
		String actual = hierarchy.toString();
		if (!expected.equals(actual)) {
			if (this.displayName) System.out.println(getName()+" actual result is:");
			System.out.println(displayString(actual, this.tabs) + this.endChar);
		}
		assertEquals("Unexpected type hierarchy", expected, actual);
	}
	protected void assertMarkers(String message, String expectedMarkers, IJavaProject project) throws CoreException {
		waitForAutoBuild();
		IMarker[] markers = project.getProject().findMarkers(IJavaModelMarker.BUILDPATH_PROBLEM_MARKER, false, IResource.DEPTH_ZERO);
		sortMarkers(markers);
		assertMarkers(message, expectedMarkers, markers);
	}
	protected void sortMarkers(IMarker[] markers) {
		org.eclipse.jdt.internal.core.util.Util.Comparer comparer = new org.eclipse.jdt.internal.core.util.Util.Comparer() {
			public int compare(Object a, Object b) {
				IMarker markerA = (IMarker)a;
				IMarker markerB = (IMarker)b;
				return markerA.getAttribute(IMarker.MESSAGE, "").compareTo(markerB.getAttribute(IMarker.MESSAGE, "")); //$NON-NLS-1$ //$NON-NLS-2$
			}
		};
		org.eclipse.jdt.internal.core.util.Util.sort(markers, comparer);
	}
	protected void assertMarkers(String message, String expectedMarkers, IMarker[] markers) throws CoreException {
		StringBuffer buffer = new StringBuffer();
		if (markers != null) {
			for (int i = 0, length = markers.length; i < length; i++) {
				IMarker marker = markers[i];
				buffer.append(marker.getAttribute(IMarker.MESSAGE));
				if (i != length-1) {
					buffer.append("\n");
				}
			}
		}
		String actual = buffer.toString();
		if (!expectedMarkers.equals(actual)) {
		 	System.out.println(displayString(actual, 2));
		}
		assertEquals(message, expectedMarkers, actual);
	}

	protected void assertMemberValuePairEquals(String expected, IMemberValuePair member) throws JavaModelException {
		StringBuffer buffer = new StringBuffer();
		appendAnnotationMember(buffer, member);
		String actual = buffer.toString();
		if (!expected.equals(actual)) {
			System.out.println(displayString(actual, 2) + this.endChar);
		}
		assertEquals("Unexpected member value pair", expected, actual);
	}

	protected void assertProblems(String message, String expected, ProblemRequestor problemRequestor) {
		String actual = org.eclipse.jdt.core.tests.util.Util.convertToIndependantLineDelimiter(problemRequestor.problems.toString());
		String independantExpectedString = org.eclipse.jdt.core.tests.util.Util.convertToIndependantLineDelimiter(expected);
		if (!independantExpectedString.equals(actual)){
		 	System.out.println(org.eclipse.jdt.core.tests.util.Util.displayString(actual, this.tabs));
		}
		assertEquals(
			message,
			independantExpectedString,
			actual);
	}
	/*
	 * Asserts that the given actual source (usually coming from a file content) is equal to the expected one.
	 * Note that 'expected' is assumed to have the '\n' line separator.
	 * The line separators in 'actual' are converted to '\n' before the comparison.
	 */
	protected void assertSourceEquals(String message, String expected, String actual) {
		if (actual == null) {
			assertEquals(message, expected, null);
			return;
		}
		actual = org.eclipse.jdt.core.tests.util.Util.convertToIndependantLineDelimiter(actual);
		if (!actual.equals(expected)) {
			System.out.println("Expected source in "+getName()+" should be:");
			System.out.print(org.eclipse.jdt.core.tests.util.Util.displayString(actual.toString(), 2));
			System.out.println(this.endChar);
		}
		assertEquals(message, expected, actual);
	}
	protected void assertAnnotationsEqual(String expected, IAnnotation[] annotations) throws JavaModelException {
		StringBuffer buffer = new StringBuffer();
		for (int i = 0; i < annotations.length; i++) {
			IAnnotation annotation = annotations[i];
			appendAnnotation(buffer, annotation);
			buffer.append("\n");
		}
		String actual = buffer.toString();
		if (!expected.equals(actual)) {
			System.out.println(displayString(actual, 2) + this.endChar);
		}
		assertEquals("Unexpected annotations", expected, actual);
	}

	private void appendAnnotation(StringBuffer buffer, IAnnotation annotation) throws JavaModelException {
		buffer.append('@');
		buffer.append(annotation.getElementName());
		IMemberValuePair[] members = annotation.getMemberValuePairs();
		int length = members.length;
		if (length > 0) {
			buffer.append('(');
			for (int i = 0; i < length; i++) {
				appendAnnotationMember(buffer, members[i]);
				if (i < length-1)
					buffer.append(", ");
			}
			buffer.append(')');
		}
	}

	private void appendAnnotationMember(StringBuffer buffer, IMemberValuePair member) throws JavaModelException {
		if (member == null) {
			buffer.append("<null>");
			return;
		}
		String name = member.getMemberName();
		if (!"value".equals(name)) {
			buffer.append(name);
			buffer.append('=');
		}
		int kind = member.getValueKind();
		Object value = member.getValue();
		if (value instanceof Object[]) {
			if (kind == IMemberValuePair.K_UNKNOWN)
				buffer.append("[unknown]");
			buffer.append('{');
			Object[] array = (Object[]) value;
			for (int i = 0, length = array.length; i < length; i++) {
				appendAnnotationMemberValue(buffer, array[i], kind);
				if (i < length-1)
					buffer.append(", ");
			}
			buffer.append('}');
		} else {
			appendAnnotationMemberValue(buffer, value, kind);
		}
	}

	private void appendAnnotationMemberValue(StringBuffer buffer, Object value, int kind) throws JavaModelException {
		if (value == null) {
			buffer.append("<null>");
			return;
		}
		switch(kind) {
		case IMemberValuePair.K_INT:
			buffer.append("(int)");
			buffer.append(value);
			break;
		case IMemberValuePair.K_BYTE:
			buffer.append("(byte)");
			buffer.append(value);
			break;
		case IMemberValuePair.K_SHORT:
			buffer.append("(short)");
			buffer.append(value);
			break;
		case IMemberValuePair.K_CHAR:
			buffer.append('\'');
			buffer.append(value);
			buffer.append('\'');
			break;
		case IMemberValuePair.K_FLOAT:
			buffer.append(value);
			buffer.append('f');
			break;
		case IMemberValuePair.K_DOUBLE:
			buffer.append("(double)");
			buffer.append(value);
			break;
		case IMemberValuePair.K_BOOLEAN:
			buffer.append(value);
			break;
		case IMemberValuePair.K_LONG:
			buffer.append(value);
			buffer.append('L');
			break;
		case IMemberValuePair.K_STRING:
			buffer.append('\"');
			buffer.append(value);
			buffer.append('\"');
			break;
		case IMemberValuePair.K_ANNOTATION:
			appendAnnotation(buffer, (IAnnotation) value);
			break;
		case IMemberValuePair.K_CLASS:
			buffer.append(value);
			buffer.append(".class");
			break;
		case IMemberValuePair.K_QUALIFIED_NAME:
			buffer.append(value);
			break;
		case IMemberValuePair.K_SIMPLE_NAME:
			buffer.append(value);
			break;
		case IMemberValuePair.K_UNKNOWN:
			appendAnnotationMemberValue(buffer, value, getValueKind(value));
			break;
		default:
			buffer.append("<Unknown value: (" + (value == null ? "" : value.getClass().getName()) + ") " + value + ">");
			break;
		}
	}
	private int getValueKind(Object value) {
		if (value instanceof Integer) {
			return IMemberValuePair.K_INT;
		} else if (value instanceof Byte) {
			return IMemberValuePair.K_BYTE;
		} else if (value instanceof Short) {
			return IMemberValuePair.K_SHORT;
		} else if (value instanceof Character) {
			return IMemberValuePair.K_CHAR;
		} else if (value instanceof Float) {
			return IMemberValuePair.K_FLOAT;
		} else if (value instanceof Double) {
			return IMemberValuePair.K_DOUBLE;
		} else if (value instanceof Boolean) {
			return IMemberValuePair.K_BOOLEAN;
		} else if (value instanceof Long) {
			return IMemberValuePair.K_LONG;
		} else if (value instanceof String) {
			return IMemberValuePair.K_STRING;
		}
		return -1;

	}
	/*
	 * Ensures that the toString() of the given AST node is as expected.
	 */
	public void assertASTNodeEquals(String message, String expected, ASTNode actual) {
		String actualString = actual == null ? "null" : actual.toString();
		assertSourceEquals(message, expected, actualString);
	}
	/**
	 * Ensures the elements are present after creation.
	 */
	public void assertCreation(IJavaElement[] newElements) {
		for (int i = 0; i < newElements.length; i++) {
			IJavaElement newElement = newElements[i];
			assertTrue("Element should be present after creation", newElement.exists());
		}
	}
	protected void assertClasspathEquals(IClasspathEntry[] classpath, String expected) {
		String actual;
		if (classpath == null) {
			actual = "<null>";
		} else {
			StringBuffer buffer = new StringBuffer();
			int length = classpath.length;
			for (int i=0; i<length; i++) {
				buffer.append(classpath[i]);
				if (i < length-1)
					buffer.append('\n');
			}
			actual = buffer.toString();
		}
		if (!actual.equals(expected)) {
		 	System.out.print(org.eclipse.jdt.core.tests.util.Util.displayString(actual, 2));
		}
		assertEquals(expected, actual);
	}
	/**
	 * Ensures the element is present after creation.
	 */
	public void assertCreation(IJavaElement newElement) {
		assertCreation(new IJavaElement[] {newElement});
	}
	/**
	 * Creates an operation to delete the given elements, asserts
	 * the operation is successful, and ensures the elements are no
	 * longer present in the model.
	 */
	public void assertDeletion(IJavaElement[] elementsToDelete) throws JavaModelException {
		IJavaElement elementToDelete = null;
		for (int i = 0; i < elementsToDelete.length; i++) {
			elementToDelete = elementsToDelete[i];
			assertTrue("Element must be present to be deleted", elementToDelete.exists());
		}

		getJavaModel().delete(elementsToDelete, false, null);

		for (int i = 0; i < elementsToDelete.length; i++) {
			elementToDelete = elementsToDelete[i];
			assertTrue("Element should not be present after deletion: " + elementToDelete, !elementToDelete.exists());
		}
	}
	protected void assertDeltas(String message, String expected) {
		String actual = this.deltaListener.toString();
		if (!expected.equals(actual)) {
			System.out.println(displayString(actual, 2));
			System.err.println(this.deltaListener.stackTraces.toString());
		}
		assertEquals(
			message,
			expected,
			actual);
	}
	protected void assertDeltas(String message, String expected, IJavaElementDelta delta) {
		String actual = delta == null ? "<null>" : delta.toString();
		if (!expected.equals(actual)) {
			System.out.println(displayString(actual, 2));
			System.err.println(this.deltaListener.stackTraces.toString());
		}
		assertEquals(
			message,
			expected,
			actual);
	}
	protected void assertTypesEqual(String message, String expected, IType[] types) {
		assertTypesEqual(message, expected, types, true);
	}
	protected void assertTypesEqual(String message, String expected, IType[] types, boolean sort) {
		if (sort) sortTypes(types);
		StringBuffer buffer = new StringBuffer();
		for (int i = 0; i < types.length; i++){
			if (types[i] == null)
				buffer.append("<null>");
			else
				buffer.append(types[i].getFullyQualifiedName());
			buffer.append("\n");
		}
		String actual = buffer.toString();
		if (!expected.equals(actual)) {
			System.out.println(displayString(actual, 2) +  this.endChar);
		}
		assertEquals(message, expected, actual);
	}
	protected void assertTypeParametersEqual(String expected, ITypeParameter[] typeParameters) throws JavaModelException {
		StringBuffer buffer = new StringBuffer();
		for (int i = 0; i < typeParameters.length; i++) {
			ITypeParameter typeParameter = typeParameters[i];
			buffer.append(typeParameter.getElementName());
			String[] bounds = typeParameter.getBounds();
			int length = bounds.length;
			if (length > 0)
				buffer.append(" extends ");
			for (int j = 0; j < length; j++) {
				buffer.append(bounds[j]);
				if (j != length -1) {
					buffer.append(" & ");
				}
			}
			buffer.append("\n");
		}
		String actual = buffer.toString();
		if (!expected.equals(actual)) {
			System.out.println(displayString(actual, 3) + this.endChar);
		}
		assertEquals("Unexpected type parameters", expected, actual);
	}
	protected void assertSortedStringsEqual(String message, String expected, String[] strings) {
		Util.sort(strings);
		assertStringsEqual(message, expected, strings);
	}
	protected void assertStringsEqual(String message, String expected, String[] strings) {
		String actual = toString(strings, true/*add extra new lines*/);
		if (!expected.equals(actual)) {
			System.out.println(displayString(actual, this.tabs) + this.endChar);
		}
		assertEquals(message, expected, actual);
	}
	protected void assertStringsEqual(String message, String[] expectedStrings, String[] actualStrings) {
		String expected = toString(expectedStrings, false/*don't add extra new lines*/);
		String actual = toString(actualStrings, false/*don't add extra new lines*/);
		if (!expected.equals(actual)) {
			System.out.println(displayString(actual, this.tabs) + this.endChar);
		}
		assertEquals(message, expected, actual);
	}
	/**
	 * Attaches a source zip to the given jar package fragment root.
	 */
	protected void attachSource(IPackageFragmentRoot root, String sourcePath, String sourceRoot) throws JavaModelException {
		IJavaProject javaProject = root.getJavaProject();
		IClasspathEntry[] entries = (IClasspathEntry[])javaProject.getRawClasspath().clone();
		for (int i = 0; i < entries.length; i++){
			IClasspathEntry entry = entries[i];
			if (entry.getPath().toOSString().toLowerCase().equals(root.getPath().toOSString().toLowerCase())) {
				entries[i] = JavaCore.newLibraryEntry(
					root.getPath(),
					sourcePath == null ? null : new Path(sourcePath),
					sourceRoot == null ? null : new Path(sourceRoot),
					false);
				break;
			}
		}
		javaProject.setRawClasspath(entries, null);
	}
	/**
	 * Creates an operation to delete the given element, asserts
	 * the operation is successfull, and ensures the element is no
	 * longer present in the model.
	 */
	public void assertDeletion(IJavaElement elementToDelete) throws JavaModelException {
		assertDeletion(new IJavaElement[] {elementToDelete});
	}
	/**
	 * Empties the current deltas.
	 */
	public void clearDeltas() {
		this.deltaListener.flush();
	}
	protected IJavaElement[] codeSelect(ISourceReference sourceReference, String selectAt, String selection) throws JavaModelException {
		String str = sourceReference.getSource();
		int start = str.indexOf(selectAt);
		int length = selection.length();
		return ((ICodeAssist)sourceReference).codeSelect(start, length);
	}
	protected IJavaElement[] codeSelectAt(ISourceReference sourceReference, String selectAt) throws JavaModelException {
		String str = sourceReference.getSource();
		int start = str.indexOf(selectAt) + selectAt.length();
		int length = 0;
		return ((ICodeAssist)sourceReference).codeSelect(start, length);
	}
	/**
	 * Copy file from src (path to the original file) to dest (path to the destination file).
	 */
	public void copy(File src, File dest) throws IOException {
		// read source bytes
		byte[] srcBytes = read(src);

		if (convertToIndependantLineDelimiter(src)) {
			String contents = new String(srcBytes);
			contents = org.eclipse.jdt.core.tests.util.Util.convertToIndependantLineDelimiter(contents);
			srcBytes = contents.getBytes();
		}

		// write bytes to dest
		FileOutputStream out = new FileOutputStream(dest);
		try {
			out.write(srcBytes);
		} finally {
			out.close();
		}
	}

	public boolean convertToIndependantLineDelimiter(File file) {
		return file.getName().endsWith(".java");
	}

	/**
	 * Copy the given source directory (and all its contents) to the given target directory.
	 */
	protected void copyDirectory(File source, File target) throws IOException {
		if (!target.exists()) {
			target.mkdirs();
		}
		File[] files = source.listFiles();
		if (files == null) return;
		for (int i = 0; i < files.length; i++) {
			File sourceChild = files[i];
			String name =  sourceChild.getName();
			if (name.equals("CVS") || name.equals(".svn")) continue;
			File targetChild = new File(target, name);
			if (sourceChild.isDirectory()) {
				copyDirectory(sourceChild, targetChild);
			} else {
				copy(sourceChild, targetChild);
			}
		}
	}
	protected IFolder createFolder(IPath path) throws CoreException {
		final IFolder folder = getWorkspaceRoot().getFolder(path);
		getWorkspace().run(new IWorkspaceRunnable() {
			public void run(IProgressMonitor monitor) throws CoreException {
				IContainer parent = folder.getParent();
				if (parent instanceof IFolder && !parent.exists()) {
					createFolder(parent.getFullPath());
				}
				folder.create(true, true, null);
			}
		},
		null);

		return folder;
	}
	protected void createJar(String[] javaPathsAndContents, String jarPath) throws IOException {
		if (new File(jarPath).exists())
			waitAtLeast(1000); // ensure the timestamps is different
		org.eclipse.jdt.core.tests.util.Util.createJar(javaPathsAndContents, jarPath, "1.4");
	}

	/*
	}
	 * Creates a Java project where prj=src=bin and with JCL_LIB on its classpath.
	 */
	protected IJavaProject createJavaProject(String projectName) throws CoreException {
		return this.createJavaProject(projectName, new String[] {""}, new String[] {"JCL_LIB"}, "");
	}
	/*
	 * Creates a Java project with the given source folders an output location.
	 * Add those on the project's classpath.
	 */
	protected IJavaProject createJavaProject(String projectName, String[] sourceFolders, String output) throws CoreException {
		return
			this.createJavaProject(
				projectName,
				sourceFolders,
				null/*no lib*/,
				null/*no inclusion pattern*/,
				null/*no exclusion pattern*/,
				null/*no project*/,
				null/*no inclusion pattern*/,
				null/*no exclusion pattern*/,
				null/*no exported project*/,
				output,
				null/*no source outputs*/,
				null/*no inclusion pattern*/,
				null/*no exclusion pattern*/,
				"1.4"
			);
	}
	/*
	 * Creates a Java project with the given source folders an output location.
	 * Add those on the project's classpath.
	 */
	protected IJavaProject createJavaProject(String projectName, String[] sourceFolders, String output, String[] sourceOutputs) throws CoreException {
		return
			this.createJavaProject(
				projectName,
				sourceFolders,
				null/*no lib*/,
				null/*no inclusion pattern*/,
				null/*no exclusion pattern*/,
				null/*no project*/,
				null/*no inclusion pattern*/,
				null/*no exclusion pattern*/,
				null/*no exported project*/,
				output,
				sourceOutputs,
				null/*no inclusion pattern*/,
				null/*no exclusion pattern*/,
				"1.4"
			);
	}
	protected IJavaProject createJavaProject(String projectName, String[] sourceFolders, String[] libraries, String output) throws CoreException {
		return
			this.createJavaProject(
				projectName,
				sourceFolders,
				libraries,
				null/*no inclusion pattern*/,
				null/*no exclusion pattern*/,
				null/*no project*/,
				null/*no inclusion pattern*/,
				null/*no exclusion pattern*/,
				true/*combine access restrictions by default*/,
				null/*no exported project*/,
				output,
				null/*no source outputs*/,
				null/*no inclusion pattern*/,
				null/*no exclusion pattern*/,
				"1.4",
				false/*don't import*/
			);
	}
	protected IJavaProject createJavaProject(String projectName, String[] sourceFolders, String[] libraries, String output, String compliance) throws CoreException {
		return
			this.createJavaProject(
				projectName,
				sourceFolders,
				libraries,
				null/*no inclusion pattern*/,
				null/*no exclusion pattern*/,
				null/*no project*/,
				null/*no inclusion pattern*/,
				null/*no exclusion pattern*/,
				null/*no exported project*/,
				output,
				null/*no source outputs*/,
				null/*no inclusion pattern*/,
				null/*no exclusion pattern*/,
				compliance
			);
	}
	protected IJavaProject createJavaProject(String projectName, String[] sourceFolders, String[] libraries, String[] projects, String projectOutput) throws CoreException {
		return
			this.createJavaProject(
				projectName,
				sourceFolders,
				libraries,
				null/*no inclusion pattern*/,
				null/*no exclusion pattern*/,
				projects,
				null/*no inclusion pattern*/,
				null/*no exclusion pattern*/,
				null/*no exported project*/,
				projectOutput,
				null/*no source outputs*/,
				null/*no inclusion pattern*/,
				null/*no exclusion pattern*/,
				"1.4"
			);
	}
	protected SearchPattern createPattern(IJavaElement element, int limitTo) {
		return SearchPattern.createPattern(element, limitTo);
	}
	protected SearchPattern createPattern(String stringPattern, int searchFor, int limitTo, boolean isCaseSensitive) {
		int matchMode = stringPattern.indexOf('*') != -1 || stringPattern.indexOf('?') != -1
			? SearchPattern.R_PATTERN_MATCH
			: SearchPattern.R_EXACT_MATCH;
		int matchRule = isCaseSensitive ? matchMode | SearchPattern.R_CASE_SENSITIVE : matchMode;
		return SearchPattern.createPattern(stringPattern, searchFor, limitTo, matchRule);
	}
	protected IJavaProject createJavaProject(String projectName, String[] sourceFolders, String[] libraries, String[] projects, boolean[] exportedProject, String projectOutput) throws CoreException {
		return
			this.createJavaProject(
				projectName,
				sourceFolders,
				libraries,
				null/*no inclusion pattern*/,
				null/*no exclusion pattern*/,
				projects,
				null/*no inclusion pattern*/,
				null/*no exclusion pattern*/,
				exportedProject,
				projectOutput,
				null/*no source outputs*/,
				null/*no inclusion pattern*/,
				null/*no exclusion pattern*/,
				"1.4"
			);
	}
	protected IJavaProject createJavaProject(String projectName, String[] sourceFolders, String[] libraries, String[] projects, String projectOutput, String compliance) throws CoreException {
		return
			createJavaProject(
				projectName,
				sourceFolders,
				libraries,
				null/*no inclusion pattern*/,
				null/*no exclusion pattern*/,
				projects,
				null/*no inclusion pattern*/,
				null/*no exclusion pattern*/,
				null/*no exported project*/,
				projectOutput,
				null/*no source outputs*/,
				null/*no inclusion pattern*/,
				null/*no exclusion pattern*/,
				compliance
			);
		}
	protected IJavaProject createJavaProject(final String projectName, final String[] sourceFolders, final String[] libraries, final String[] projects, final boolean[] exportedProjects, final String projectOutput, final String[] sourceOutputs, final String[][] inclusionPatterns, final String[][] exclusionPatterns, final String compliance) throws CoreException {
		return
		this.createJavaProject(
			projectName,
			sourceFolders,
			libraries,
			null/*no inclusion pattern*/,
			null/*no exclusion pattern*/,
			projects,
			null/*no inclusion pattern*/,
			null/*no exclusion pattern*/,
			exportedProjects,
			projectOutput,
			sourceOutputs,
			inclusionPatterns,
			exclusionPatterns,
			compliance
		);
	}
	protected IJavaProject createJavaProject(
			final String projectName,
			final String[] sourceFolders,
			final String[] libraries,
			final String[][] librariesInclusionPatterns,
			final String[][] librariesExclusionPatterns,
			final String[] projects,
			final String[][] projectsInclusionPatterns,
			final String[][] projectsExclusionPatterns,
			final boolean[] exportedProjects,
			final String projectOutput,
			final String[] sourceOutputs,
			final String[][] inclusionPatterns,
			final String[][] exclusionPatterns,
			final String compliance) throws CoreException {
		return createJavaProject(
			projectName,
			sourceFolders,
			libraries,
			librariesInclusionPatterns,
			librariesExclusionPatterns,
			projects,
			projectsInclusionPatterns,
			projectsExclusionPatterns,
			true, // combine access restrictions by default
			exportedProjects,
			projectOutput,
			sourceOutputs,
			inclusionPatterns,
			exclusionPatterns,
			compliance,
			false/*don't import*/);
	}
	protected IJavaProject createJavaProject(
			final String projectName,
			final String[] sourceFolders,
			final String[] libraries,
			final String[][] librariesInclusionPatterns,
			final String[][] librariesExclusionPatterns,
			final String[] projects,
			final String[][] projectsInclusionPatterns,
			final String[][] projectsExclusionPatterns,
			final boolean combineAccessRestrictions,
			final boolean[] exportedProjects,
			final String projectOutput,
			final String[] sourceOutputs,
			final String[][] inclusionPatterns,
			final String[][] exclusionPatterns,
			final String compliance,
			final boolean simulateImport) throws CoreException {
		final IJavaProject[] result = new IJavaProject[1];
		IWorkspaceRunnable create = new IWorkspaceRunnable() {
			public void run(IProgressMonitor monitor) throws CoreException {
				// create project
				createProject(projectName);

				// set java nature
				addJavaNature(projectName);

				// create classpath entries
				IProject project = getWorkspaceRoot().getProject(projectName);
				IPath projectPath = project.getFullPath();
				int sourceLength = sourceFolders == null ? 0 : sourceFolders.length;
				int libLength = libraries == null ? 0 : libraries.length;
				int projectLength = projects == null ? 0 : projects.length;
				IClasspathEntry[] entries = new IClasspathEntry[sourceLength+libLength+projectLength];
				for (int i= 0; i < sourceLength; i++) {
					IPath sourcePath = new Path(sourceFolders[i]);
					int segmentCount = sourcePath.segmentCount();
					if (segmentCount > 0) {
						// create folder and its parents
						IContainer container = project;
						for (int j = 0; j < segmentCount; j++) {
							IFolder folder = container.getFolder(new Path(sourcePath.segment(j)));
							if (!folder.exists()) {
								folder.create(true, true, null);
							}
							container = folder;
						}
					}
					IPath outputPath = null;
					if (sourceOutputs != null) {
						// create out folder for source entry
						outputPath = sourceOutputs[i] == null ? null : new Path(sourceOutputs[i]);
						if (outputPath != null && outputPath.segmentCount() > 0) {
							IFolder output = project.getFolder(outputPath);
							if (!output.exists()) {
								output.create(true, true, null);
							}
						}
					}
					// inclusion patterns
					IPath[] inclusionPaths;
					if (inclusionPatterns == null) {
						inclusionPaths = new IPath[0];
					} else {
						String[] patterns = inclusionPatterns[i];
						int length = patterns.length;
						inclusionPaths = new IPath[length];
						for (int j = 0; j < length; j++) {
							String inclusionPattern = patterns[j];
							inclusionPaths[j] = new Path(inclusionPattern);
						}
					}
					// exclusion patterns
					IPath[] exclusionPaths;
					if (exclusionPatterns == null) {
						exclusionPaths = new IPath[0];
					} else {
						String[] patterns = exclusionPatterns[i];
						int length = patterns.length;
						exclusionPaths = new IPath[length];
						for (int j = 0; j < length; j++) {
							String exclusionPattern = patterns[j];
							exclusionPaths[j] = new Path(exclusionPattern);
						}
					}
					// create source entry
					entries[i] =
						JavaCore.newSourceEntry(
							projectPath.append(sourcePath),
							inclusionPaths,
							exclusionPaths,
							outputPath == null ? null : projectPath.append(outputPath)
						);
				}
				for (int i= 0; i < libLength; i++) {
					String lib = libraries[i];
					if (lib.startsWith("JCL")) {
						try {
							// ensure JCL variables are set
							setUpJCLClasspathVariables(compliance);
						} catch (IOException e) {
							e.printStackTrace();
						}
					}

					// accessible files
					IPath[] accessibleFiles;
					if (librariesInclusionPatterns == null) {
						accessibleFiles = new IPath[0];
					} else {
						String[] patterns = librariesInclusionPatterns[i];
						int length = patterns.length;
						accessibleFiles = new IPath[length];
						for (int j = 0; j < length; j++) {
							String inclusionPattern = patterns[j];
							accessibleFiles[j] = new Path(inclusionPattern);
						}
					}
					// non accessible files
					IPath[] nonAccessibleFiles;
					if (librariesExclusionPatterns == null) {
						nonAccessibleFiles = new IPath[0];
					} else {
						String[] patterns = librariesExclusionPatterns[i];
						int length = patterns.length;
						nonAccessibleFiles = new IPath[length];
						for (int j = 0; j < length; j++) {
							String exclusionPattern = patterns[j];
							nonAccessibleFiles[j] = new Path(exclusionPattern);
						}
					}
					if (lib.indexOf(File.separatorChar) == -1 && lib.charAt(0) != '/' && lib.equals(lib.toUpperCase())) { // all upper case is a var
						char[][] vars = CharOperation.splitOn(',', lib.toCharArray());
						entries[sourceLength+i] = JavaCore.newVariableEntry(
							new Path(new String(vars[0])),
							vars.length > 1 ? new Path(new String(vars[1])) : null,
							vars.length > 2 ? new Path(new String(vars[2])) : null,
							ClasspathEntry.getAccessRules(accessibleFiles, nonAccessibleFiles), // ClasspathEntry.NO_ACCESS_RULES,
							ClasspathEntry.NO_EXTRA_ATTRIBUTES,
							false);
					} else if (lib.startsWith("org.eclipse.jdt.core.tests.model.")) { // container
						entries[sourceLength+i] = JavaCore.newContainerEntry(
								new Path(lib),
								ClasspathEntry.getAccessRules(accessibleFiles, nonAccessibleFiles),
								new IClasspathAttribute[0],
								false);
					} else {
						IPath libPath = new Path(lib);
						if (!libPath.isAbsolute() && libPath.segmentCount() > 0 && libPath.getFileExtension() == null) {
							project.getFolder(libPath).create(true, true, null);
							libPath = projectPath.append(libPath);
						}
						entries[sourceLength+i] = JavaCore.newLibraryEntry(
								libPath,
								null,
								null,
								ClasspathEntry.getAccessRules(accessibleFiles, nonAccessibleFiles),
								new IClasspathAttribute[0],
								false);
					}
				}
				for  (int i= 0; i < projectLength; i++) {
					boolean isExported = exportedProjects != null && exportedProjects.length > i && exportedProjects[i];

					// accessible files
					IPath[] accessibleFiles;
					if (projectsInclusionPatterns == null) {
						accessibleFiles = new IPath[0];
					} else {
						String[] patterns = projectsInclusionPatterns[i];
						int length = patterns.length;
						accessibleFiles = new IPath[length];
						for (int j = 0; j < length; j++) {
							String inclusionPattern = patterns[j];
							accessibleFiles[j] = new Path(inclusionPattern);
						}
					}
					// non accessible files
					IPath[] nonAccessibleFiles;
					if (projectsExclusionPatterns == null) {
						nonAccessibleFiles = new IPath[0];
					} else {
						String[] patterns = projectsExclusionPatterns[i];
						int length = patterns.length;
						nonAccessibleFiles = new IPath[length];
						for (int j = 0; j < length; j++) {
							String exclusionPattern = patterns[j];
							nonAccessibleFiles[j] = new Path(exclusionPattern);
						}
					}

					entries[sourceLength+libLength+i] =
						JavaCore.newProjectEntry(
								new Path(projects[i]),
								ClasspathEntry.getAccessRules(accessibleFiles, nonAccessibleFiles),
								combineAccessRestrictions,
								new IClasspathAttribute[0],
								isExported);
				}

				// create project's output folder
				IPath outputPath = new Path(projectOutput);
				if (outputPath.segmentCount() > 0) {
					IFolder output = project.getFolder(outputPath);
					if (!output.exists()) {
						output.create(true, true, monitor);
					}
				}

				// set classpath and output location
				JavaProject javaProject = (JavaProject) JavaCore.create(project);
				if (simulateImport)
					javaProject.writeFileEntries(entries, projectPath.append(outputPath));
				else
					javaProject.setRawClasspath(entries, projectPath.append(outputPath), monitor);

				// set compliance level options
				if ("1.5".equals(compliance)) {
					Map options = new HashMap();
					options.put(CompilerOptions.OPTION_Compliance, CompilerOptions.VERSION_1_5);
					options.put(CompilerOptions.OPTION_Source, CompilerOptions.VERSION_1_5);
					options.put(CompilerOptions.OPTION_TargetPlatform, CompilerOptions.VERSION_1_5);
					javaProject.setOptions(options);
				}

				result[0] = javaProject;
			}
		};
		getWorkspace().run(create, null);
		return result[0];
	}
	protected IJavaProject importJavaProject(String projectName, String[] sourceFolders, String[] libraries, String output) throws CoreException {
		return
			createJavaProject(
				projectName,
				sourceFolders,
				libraries,
				null/*no inclusion pattern*/,
				null/*no exclusion pattern*/,
				null/*no project*/,
				null/*no inclusion pattern*/,
				null/*no exclusion pattern*/,
				true/*combine access restrictions by default*/,
				null/*no exported project*/,
				output,
				null/*no source outputs*/,
				null/*no inclusion pattern*/,
				null/*no exclusion pattern*/,
				"1.4",
				true/*import*/
			);
	}
	/*
	 * Create simple project.
	 */
	protected IProject createProject(final String projectName) throws CoreException {
		final IProject project = getProject(projectName);
		IWorkspaceRunnable create = new IWorkspaceRunnable() {
			public void run(IProgressMonitor monitor) throws CoreException {
				project.create(null);
				project.open(null);
			}
		};
		getWorkspace().run(create, null);
		return project;
	}
	public void deleteResource(File resource) {
		int retryCount = 0;
		while (++retryCount <= 60) { // wait 1 minute at most
			if (org.eclipse.jdt.core.tests.util.Util.delete(resource)) {
				break;
			}
		}
	}
	protected void deleteFolder(IPath folderPath) throws CoreException {
		deleteResource(getFolder(folderPath));
	}
	protected void deleteProject(String projectName) throws CoreException {
		IProject project = getProject(projectName);
		if (project.exists() && !project.isOpen()) { // force opening so that project can be deleted without logging (see bug 23629)
			project.open(null);
		}
		deleteResource(project);
	}
	protected void deleteProject(IJavaProject project) throws CoreException {
		if (project.exists() && !project.isOpen()) { // force opening so that project can be deleted without logging (see bug 23629)
			project.open(null);
		}
		deleteResource(project.getProject());
	}

	/**
	 * Batch deletion of projects
	 */
	protected void deleteProjects(final String[] projectNames) throws CoreException {
		ResourcesPlugin.getWorkspace().run(new IWorkspaceRunnable() {
			public void run(IProgressMonitor monitor) throws CoreException {
				if (projectNames != null){
					for (int i = 0, max = projectNames.length; i < max; i++){
						if (projectNames[i] != null)
							deleteProject(projectNames[i]);
					}
				}
			}
		},
		null);
	}
	/**
	 * Delete this resource.
	 */
	public void deleteResource(IResource resource) throws CoreException {
		int retryCount = 0; // wait 1 minute at most
		while (++retryCount <= 60) {
			if (org.eclipse.jdt.core.tests.util.Util.delete(resource)) {
				return;
			} else {
				System.gc();
			}
		}
		throw new RuntimeException("Could not delete " + resource.getFullPath());
	}
	/**
	 * Returns true if this delta is flagged as having changed children.
	 */
	protected boolean deltaChildrenChanged(IJavaElementDelta delta) {
		return delta.getKind() == IJavaElementDelta.CHANGED &&
			(delta.getFlags() & IJavaElementDelta.F_CHILDREN) != 0;
	}
	/**
	 * Returns true if this delta is flagged as having had a content change
	 */
	protected boolean deltaContentChanged(IJavaElementDelta delta) {
		return delta.getKind() == IJavaElementDelta.CHANGED &&
			(delta.getFlags() & IJavaElementDelta.F_CONTENT) != 0;
	}
	/**
	 * Returns true if this delta is flagged as having moved from a location
	 */
	protected boolean deltaMovedFrom(IJavaElementDelta delta) {
		return delta.getKind() == IJavaElementDelta.ADDED &&
			(delta.getFlags() & IJavaElementDelta.F_MOVED_FROM) != 0;
	}
	/**
	 * Returns true if this delta is flagged as having moved to a location
	 */
	protected boolean deltaMovedTo(IJavaElementDelta delta) {
		return delta.getKind() == IJavaElementDelta.REMOVED &&
			(delta.getFlags() & IJavaElementDelta.F_MOVED_TO) != 0;
	}
	/**
	 * Ensure that the positioned element is in the correct position within the parent.
	 */
	public void ensureCorrectPositioning(IParent container, IJavaElement sibling, IJavaElement positioned) throws JavaModelException {
		IJavaElement[] children = container.getChildren();
		if (sibling != null) {
			// find the sibling
			boolean found = false;
			for (int i = 0; i < children.length; i++) {
				if (children[i].equals(sibling)) {
					assertTrue("element should be before sibling", i > 0 && children[i - 1].equals(positioned));
					found = true;
					break;
				}
			}
			assertTrue("Did not find sibling", found);
		}
	}
	/**
	 * Returns the specified compilation unit in the given project, root, and
	 * package fragment or <code>null</code> if it does not exist.
	 */
	public IClassFile getClassFile(String projectName, String rootPath, String packageName, String className) throws JavaModelException {
		IPackageFragment pkg= getPackageFragment(projectName, rootPath, packageName);
		if (pkg == null) {
			return null;
		}
		return pkg.getClassFile(className);
	}
	protected ICompilationUnit getCompilationUnit(String path) {
		return (ICompilationUnit)JavaCore.create(getFile(path));
	}
	/**
	 * Returns the specified compilation unit in the given project, root, and
	 * package fragment or <code>null</code> if it does not exist.
	 */
	public ICompilationUnit getCompilationUnit(String projectName, String rootPath, String packageName, String cuName) throws JavaModelException {
		IPackageFragment pkg= getPackageFragment(projectName, rootPath, packageName);
		if (pkg == null) {
			return null;
		}
		return pkg.getCompilationUnit(cuName);
	}
	/**
	 * Returns the specified compilation unit in the given project, root, and
	 * package fragment or <code>null</code> if it does not exist.
	 */
	public ICompilationUnit[] getCompilationUnits(String projectName, String rootPath, String packageName) throws JavaModelException {
		IPackageFragment pkg= getPackageFragment(projectName, rootPath, packageName);
		if (pkg == null) {
			return null;
		}
		return pkg.getCompilationUnits();
	}
	protected ICompilationUnit getCompilationUnitFor(IJavaElement element) {

		if (element instanceof ICompilationUnit) {
			return (ICompilationUnit)element;
		}

		if (element instanceof IMember) {
			return ((IMember)element).getCompilationUnit();
		}

		if (element instanceof IPackageDeclaration ||
			element instanceof IImportDeclaration) {
				return (ICompilationUnit)element.getParent();
			}

		return null;

	}
	/**
	 * Returns the last delta for the given element from the cached delta.
	 */
	protected IJavaElementDelta getDeltaFor(IJavaElement element) {
		return getDeltaFor(element, false);
	}
	/**
	 * Returns the delta for the given element from the cached delta.
	 * If the boolean is true returns the first delta found.
	 */
	protected IJavaElementDelta getDeltaFor(IJavaElement element, boolean returnFirst) {
		IJavaElementDelta[] deltas = this.deltaListener.deltas;
		if (deltas == null) return null;
		IJavaElementDelta result = null;
		for (int i = 0; i < deltas.length; i++) {
			IJavaElementDelta delta = searchForDelta(element, this.deltaListener.deltas[i]);
			if (delta != null) {
				if (returnFirst) {
					return delta;
				}
				result = delta;
			}
		}
		return result;
	}

	protected File getExternalFile(String relativePath) {
		return new File(getExternalPath(), relativePath);
	}

	protected String getExternalResourcePath(String name) {
		return getExternalPath() + name;
	}

	/**
	 * Returns the IPath to the external java class library (e.g. jclMin.jar)
	 */
	protected IPath getExternalJCLPath() {
		return new Path(getExternalJCLPathString(""));
	}
	/**
	 * Returns the IPath to the external java class library (e.g. jclMin.jar)
	 */
	protected IPath getExternalJCLPath(String compliance) {
		return new Path(getExternalJCLPathString(compliance));
	}
	/**
	 * Returns the java.io path to the external java class library (e.g. jclMin.jar)
	 */
	protected String getExternalJCLPathString() {
		return getExternalJCLPathString("");
	}
	/**
	 * Returns the java.io path to the external java class library (e.g. jclMin.jar)
	 */
	protected String getExternalJCLPathString(String compliance) {
		return getExternalPath() + "jclMin" + compliance + ".jar";
	}
	/**
	 * Returns the IPath to the root source of the external java class library (e.g. "src")
	 */
	protected IPath getExternalJCLRootSourcePath() {
		return new Path("src");
	}
	/**
	 * Returns the IPath to the source of the external java class library (e.g. jclMinsrc.zip)
	 */
	protected IPath getExternalJCLSourcePath() {
		return new Path(getExternalJCLSourcePathString(""));
	}
	/**
	 * Returns the IPath to the source of the external java class library (e.g. jclMinsrc.zip)
	 */
	protected IPath getExternalJCLSourcePath(String compliance) {
		return new Path(getExternalJCLSourcePathString(compliance));
	}
	/**
	 * Returns the java.io path to the source of the external java class library (e.g. jclMinsrc.zip)
	 */
	protected String getExternalJCLSourcePathString() {
		return getExternalJCLSourcePathString("");
	}
	/**
	 * Returns the java.io path to the source of the external java class library (e.g. jclMinsrc.zip)
	 */
	protected String getExternalJCLSourcePathString(String compliance) {
		return getExternalPath() + "jclMin" + compliance + "src.zip";
	}
	/*
	 * Returns the OS path to the external directory that contains external jar files.
	 * This path ends with a File.separatorChar.
	 */
	protected String getExternalPath() {
		if (EXTERNAL_JAR_DIR_PATH == null)
			try {
				String path = getWorkspaceRoot().getLocation().toFile().getParentFile().getCanonicalPath();
				if (path.charAt(path.length()-1) != File.separatorChar)
					path += File.separatorChar;
				EXTERNAL_JAR_DIR_PATH = path;
			} catch (IOException e) {
				e.printStackTrace();
			}
		return EXTERNAL_JAR_DIR_PATH;
	}
	protected IFile getFile(String path) {
		return getWorkspaceRoot().getFile(new Path(path));
	}
	protected IFolder getFolder(IPath path) {
		return getWorkspaceRoot().getFolder(path);
	}
	/**
	 * Returns the Java Model this test suite is running on.
	 */
	public IJavaModel getJavaModel() {
		return JavaCore.create(getWorkspaceRoot());
	}
	/**
	 * Returns the Java Project with the given name in this test
	 * suite's model. This is a convenience method.
	 */
	public IJavaProject getJavaProject(String name) {
		IProject project = getProject(name);
		return JavaCore.create(project);
	}
	protected ILocalVariable getLocalVariable(ISourceReference cu, String selectAt, String selection) throws JavaModelException {
		IJavaElement[] elements = codeSelect(cu, selectAt, selection);
		if (elements.length == 0) return null;
		if (elements[0] instanceof ILocalVariable) {
			return (ILocalVariable)elements[0];
		}
		return null;
	}
	protected ILocalVariable getLocalVariable(String cuPath, String selectAt, String selection) throws JavaModelException {
		ISourceReference cu = getCompilationUnit(cuPath);
		return getLocalVariable(cu, selectAt, selection);
	}
	protected String getNameSource(String cuSource, IJavaElement element) throws JavaModelException {
		ISourceRange nameRange;
		switch (element.getElementType()) {
			case IJavaElement.TYPE_PARAMETER:
				nameRange = ((ITypeParameter) element).getNameRange();
				break;
			case IJavaElement.ANNOTATION:
				nameRange = ((IAnnotation) element).getNameRange();
				break;
			default:
				nameRange = ((IMember) element).getNameRange();
				break;
		}
		return getSource(cuSource, nameRange);
	}
	protected String getSource(String cuSource, ISourceRange sourceRange) throws JavaModelException {
		int start = sourceRange.getOffset();
		int end = start+sourceRange.getLength();
		String actualSource = start >= 0 && end >= start ? cuSource.substring(start, end) : "";
		return actualSource;
	}
	/**
	 * Returns the specified package fragment in the given project and root, or
	 * <code>null</code> if it does not exist.
	 * The rootPath must be specified as a project relative path. The empty
	 * path refers to the default package fragment.
	 */
	public IPackageFragment getPackageFragment(String projectName, String rootPath, String packageName) throws JavaModelException {
		IPackageFragmentRoot root= getPackageFragmentRoot(projectName, rootPath);
		if (root == null) {
			return null;
		}
		return root.getPackageFragment(packageName);
	}
	/**
	 * Returns the specified package fragment root in the given project, or
	 * <code>null</code> if it does not exist.
	 * If relative, the rootPath must be specified as a project relative path.
	 * The empty path refers to the package fragment root that is the project
	 * folder iteslf.
	 * If absolute, the rootPath refers to either an external jar, or a resource
	 * internal to the workspace
	 */
	public IPackageFragmentRoot getPackageFragmentRoot(
		String projectName,
		String rootPath)
		throws JavaModelException {

		IJavaProject project = getJavaProject(projectName);
		if (project == null) {
			return null;
		}
		IPath path = new Path(rootPath);
		if (path.isAbsolute()) {
			IWorkspaceRoot workspaceRoot = ResourcesPlugin.getWorkspace().getRoot();
			IResource resource = workspaceRoot.findMember(path);
			IPackageFragmentRoot root;
			if (resource == null) {
				// external jar
				root = project.getPackageFragmentRoot(rootPath);
			} else {
				// resource in the workspace
				root = project.getPackageFragmentRoot(resource);
			}
			return root;
		} else {
			IPackageFragmentRoot[] roots = project.getPackageFragmentRoots();
			if (roots == null || roots.length == 0) {
				return null;
			}
			for (int i = 0; i < roots.length; i++) {
				IPackageFragmentRoot root = roots[i];
				if (!root.isExternal()
					&& root.getUnderlyingResource().getProjectRelativePath().equals(path)) {
					return root;
				}
			}
		}
		return null;
	}
	protected IProject getProject(String project) {
		return getWorkspaceRoot().getProject(project);
	}
	/**
	 * Returns the OS path to the directory that contains this plugin.
	 */
	protected String getPluginDirectoryPath() {
		try {
			URL platformURL = Platform.getBundle("org.eclipse.jdt.core.tests.model").getEntry("/");
			return new File(FileLocator.toFileURL(platformURL).getFile()).getAbsolutePath();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return null;
	}
	public String getSourceWorkspacePath() {
		return getPluginDirectoryPath() +  java.io.File.separator + "workspace";
	}
	public ICompilationUnit getWorkingCopy(String path, boolean computeProblems) throws JavaModelException {
		return getWorkingCopy(path, "", computeProblems);
	}
	public ICompilationUnit getWorkingCopy(String path, String source) throws JavaModelException {
		return getWorkingCopy(path, source, false);
	}
	public ICompilationUnit getWorkingCopy(String path, String source, boolean computeProblems) throws JavaModelException {
		if (this.wcOwner == null) {
			this.wcOwner = newWorkingCopyOwner(computeProblems ? new BasicProblemRequestor() : null);
			return getWorkingCopy(path, source, this.wcOwner);
		}
		ICompilationUnit wc = getWorkingCopy(path, source, this.wcOwner);
		// Verify that compute problem parameter is compatible with working copy problem requestor
		if (computeProblems) {
			assertNotNull("Cannot compute problems if the problem requestor of the working copy owner is set to null!", this.wcOwner.getProblemRequestor(wc));
		} else {
			assertNull("Cannot ignore problems if the problem requestor of the working copy owner is not set to null!", this.wcOwner.getProblemRequestor(wc));
		}
		return wc;
	}
	public ICompilationUnit getWorkingCopy(String path, String source, WorkingCopyOwner owner) throws JavaModelException {
		ICompilationUnit workingCopy = getCompilationUnit(path);
		if (owner != null)
			workingCopy = workingCopy.getWorkingCopy(owner, null/*no progress monitor*/);
		else
			workingCopy.becomeWorkingCopy(null/*no progress monitor*/);
		workingCopy.getBuffer().setContents(source);
		if (owner != null) {
			IProblemRequestor problemRequestor = owner.getProblemRequestor(workingCopy);
			if (problemRequestor instanceof ProblemRequestor) {
				((ProblemRequestor) problemRequestor).initialize(source.toCharArray());
			}
		}
		workingCopy.makeConsistent(null/*no progress monitor*/);
		return workingCopy;
	}
	/**
	 * This method is still necessary when we need to use an owner and a specific problem requestor
	 * (typically while using primary owner).
	 * @deprecated
	 */
	public ICompilationUnit getWorkingCopy(String path, String source, WorkingCopyOwner owner, IProblemRequestor problemRequestor) throws JavaModelException {
		ICompilationUnit workingCopy = getCompilationUnit(path);
		if (owner != null)
			workingCopy = workingCopy.getWorkingCopy(owner, problemRequestor, null/*no progress monitor*/);
		else
			workingCopy.becomeWorkingCopy(problemRequestor, null/*no progress monitor*/);
		workingCopy.getBuffer().setContents(source);
		if (problemRequestor instanceof ProblemRequestor)
			((ProblemRequestor) problemRequestor).initialize(source.toCharArray());
		workingCopy.makeConsistent(null/*no progress monitor*/);
		return workingCopy;
	}
	/**
	 * Returns the IWorkspace this test suite is running on.
	 */
	public IWorkspace getWorkspace() {
		return ResourcesPlugin.getWorkspace();
	}
	public IWorkspaceRoot getWorkspaceRoot() {
		return getWorkspace().getRoot();
	}
	protected void discardWorkingCopies(ICompilationUnit[] units) throws JavaModelException {
		if (units == null) return;
		for (int i = 0, length = units.length; i < length; i++)
			if (units[i] != null)
				units[i].discardWorkingCopy();
	}

	protected String displayString(String toPrint, int indent) {
    	char[] toDisplay =
    		CharOperation.replace(
    			toPrint.toCharArray(),
    			getExternalJCLPathString().toCharArray(),
    			"getExternalJCLPathString()".toCharArray());
		toDisplay =
    		CharOperation.replace(
    			toDisplay,
    			getExternalJCLPathString("1.5").toCharArray(),
    			"getExternalJCLPathString(\"1.5\")".toCharArray());
		toDisplay =
    		CharOperation.replace(
    			toDisplay,
    			getExternalPath().toCharArray(),
    			"getExternalPath()".toCharArray());

		toDisplay =
    		CharOperation.replace(
    			toDisplay,
    			org.eclipse.jdt.core.tests.util.Util.displayString(getExternalJCLSourcePathString(), 0).toCharArray(),
    			"getExternalJCLSourcePathString()".toCharArray());
		toDisplay =
    		CharOperation.replace(
    			toDisplay,
    			org.eclipse.jdt.core.tests.util.Util.displayString(getExternalJCLSourcePathString("1.5"), 0).toCharArray(),
    			"getExternalJCLSourcePathString(\"1.5\")".toCharArray());

    	toDisplay = org.eclipse.jdt.core.tests.util.Util.displayString(new String(toDisplay), indent).toCharArray();

    	toDisplay =
    		CharOperation.replace(
    			toDisplay,
    			"getExternalJCLPathString()".toCharArray(),
    			("\"+ getExternalJCLPathString() + \"").toCharArray());
    	toDisplay =
    		CharOperation.replace(
    			toDisplay,
    			"getExternalJCLPathString(\\\"1.5\\\")".toCharArray(),
    			("\"+ getExternalJCLPathString(\"1.5\") + \"").toCharArray());
    	toDisplay =
    		CharOperation.replace(
    			toDisplay,
    			"getExternalJCLSourcePathString()".toCharArray(),
    			("\"+ getExternalJCLSourcePathString() + \"").toCharArray());
    	toDisplay =
    		CharOperation.replace(
    			toDisplay,
    			"getExternalJCLSourcePathString(\\\"1.5\\\")".toCharArray(),
    			("\"+ getExternalJCLSourcePathString(\"1.5\") + \"").toCharArray());
    	toDisplay =
    		CharOperation.replace(
    			toDisplay,
    			"getExternalPath()".toCharArray(),
    			("\"+ getExternalPath() + \"").toCharArray());
    	return new String(toDisplay);
    }

	protected ICompilationUnit newExternalWorkingCopy(String name, final String contents) throws JavaModelException {
		return newExternalWorkingCopy(name, null/*no classpath*/, null/*no problem requestor*/, contents);
	}
	protected ICompilationUnit newExternalWorkingCopy(String name, IClasspathEntry[] classpath, final IProblemRequestor problemRequestor, final String contents) throws JavaModelException {
		WorkingCopyOwner owner = new WorkingCopyOwner() {
			public IBuffer createBuffer(ICompilationUnit wc) {
				IBuffer buffer = super.createBuffer(wc);
				buffer.setContents(contents);
				return buffer;
			}
			public IProblemRequestor getProblemRequestor(ICompilationUnit workingCopy) {
				return problemRequestor;
			}
		};
		return owner.newWorkingCopy(name, classpath, null/*no progress monitor*/);
	}

	/**
	 * Create a new working copy owner using given problem requestor
	 * to report problem.
	 *
	 * @param problemRequestor The requestor used to report problems
	 * @return The created working copy owner
	 */
	protected WorkingCopyOwner newWorkingCopyOwner(final IProblemRequestor problemRequestor) {
		return new WorkingCopyOwner() {
			public IProblemRequestor getProblemRequestor(ICompilationUnit unit) {
				return problemRequestor;
			}
		};
	}

	public byte[] read(java.io.File file) throws java.io.IOException {
		int fileLength;
		byte[] fileBytes = new byte[fileLength = (int) file.length()];
		java.io.FileInputStream stream = new java.io.FileInputStream(file);
		int bytesRead = 0;
		int lastReadSize = 0;
		try {
			while ((lastReadSize != -1) && (bytesRead != fileLength)) {
				lastReadSize = stream.read(fileBytes, bytesRead, fileLength - bytesRead);
				bytesRead += lastReadSize;
			}
			return fileBytes;
		} finally {
			stream.close();
		}
	}

	public void refresh(final IJavaProject javaProject) throws CoreException {
		javaProject.getProject().refreshLocal(IResource.DEPTH_INFINITE, null);
		waitForManualRefresh();
	}

	protected void refreshExternalArchives(IJavaProject p) throws JavaModelException {
		waitForAutoBuild(); // ensure that the auto-build job doesn't interfere with external jar refreshing
		getJavaModel().refreshExternalArchives(new IJavaElement[] {p}, null);
	}

	protected void removeJavaNature(String projectName) throws CoreException {
		IProject project = getProject(projectName);
		IProjectDescription description = project.getDescription();
		description.setNatureIds(new String[] {});
		project.setDescription(description, null);
	}
	protected void removeLibrary(IJavaProject javaProject, String jarName, String sourceZipName) throws CoreException, IOException {
		IProject project = javaProject.getProject();
		String projectPath = '/' + project.getName() + '/';
		removeClasspathEntry(javaProject, new Path(projectPath + jarName));
		org.eclipse.jdt.core.tests.util.Util.delete(project.getFile(jarName));
		if (sourceZipName != null && sourceZipName.length() != 0) {
			org.eclipse.jdt.core.tests.util.Util.delete(project.getFile(sourceZipName));
		}
	}
	protected void removeClasspathEntry(IPath path) throws JavaModelException {
		removeClasspathEntry(this.currentProject, path);
	}
	protected void removeClasspathEntry(IJavaProject project, IPath path) throws JavaModelException {
		IClasspathEntry[] entries = project.getRawClasspath();
		int length = entries.length;
		IClasspathEntry[] newEntries = null;
		for (int i = 0; i < length; i++) {
			IClasspathEntry entry = entries[i];
			if (entry.getPath().equals(path)) {
				newEntries = new IClasspathEntry[length-1];
				if (i > 0)
					System.arraycopy(entries, 0, newEntries, 0, i);
				if (i < length-1)
				System.arraycopy(entries, i+1, newEntries, i, length-1-i);
				break;
			}
		}
		if (newEntries != null)
			project.setRawClasspath(newEntries, null);
	}

	/**
	 * Returns a delta for the given element in the delta tree
	 */
	protected IJavaElementDelta searchForDelta(IJavaElement element, IJavaElementDelta delta) {

		if (delta == null) {
			return null;
		}
		if (delta.getElement().equals(element)) {
			return delta;
		}
		for (int i= 0; i < delta.getAffectedChildren().length; i++) {
			IJavaElementDelta child= searchForDelta(element, delta.getAffectedChildren()[i]);
			if (child != null) {
				return child;
			}
		}
		return null;
	}
	protected void search(IJavaElement element, int limitTo, IJavaSearchScope scope, SearchRequestor requestor) throws CoreException {
		search(element, limitTo, SearchPattern.R_EXACT_MATCH|SearchPattern.R_CASE_SENSITIVE, scope, requestor);
	}
	protected void search(IJavaElement element, int limitTo, int matchRule, IJavaSearchScope scope, SearchRequestor requestor) throws CoreException {
		SearchPattern pattern = SearchPattern.createPattern(element, limitTo, matchRule);
		assertNotNull("Pattern should not be null", pattern);
		new SearchEngine().search(
			pattern,
			new SearchParticipant[] {SearchEngine.getDefaultSearchParticipant()},
			scope,
			requestor,
			null
		);
	}
	protected void search(String patternString, int searchFor, int limitTo, IJavaSearchScope scope, SearchRequestor requestor) throws CoreException {
		search(patternString, searchFor, limitTo, SearchPattern.R_EXACT_MATCH|SearchPattern.R_CASE_SENSITIVE, scope, requestor);
	}
	protected void search(String patternString, int searchFor, int limitTo, int matchRule, IJavaSearchScope scope, SearchRequestor requestor) throws CoreException {
		if (patternString.indexOf('*') != -1 || patternString.indexOf('?') != -1)
			matchRule |= SearchPattern.R_PATTERN_MATCH;
		SearchPattern pattern = SearchPattern.createPattern(
			patternString,
			searchFor,
			limitTo,
			matchRule);
		assertNotNull("Pattern should not be null", pattern);
		new SearchEngine().search(
			pattern,
			new SearchParticipant[] {SearchEngine.getDefaultSearchParticipant()},
			scope,
			requestor,
			null);
	}

	/*
	 * Selection of java elements.
	 */

	/*
	 * Search several occurences of a selection in a compilation unit source and returns its start and length.
	 * If occurence is negative, then perform a backward search from the end of file.
	 * If selection starts or ends with a comment (to help identification in source), it is removed from returned selection info.
	 */
	int[] selectionInfo(ICompilationUnit cu, String selection, int occurences) throws JavaModelException {
		String source = cu.getSource();
		int index = occurences < 0 ? source.lastIndexOf(selection) : source.indexOf(selection);
		int max = Math.abs(occurences)-1;
		for (int n=0; index >= 0 && n<max; n++) {
			index = occurences < 0 ? source.lastIndexOf(selection, index) : source.indexOf(selection, index+selection.length());
		}
		StringBuffer msg = new StringBuffer("Selection '");
		msg.append(selection);
		if (index >= 0) {
			if (selection.startsWith("/**")) { // comment is before
				int start = source.indexOf("*/", index);
				if (start >=0) {
					return new int[] { start+2, selection.length()-(start+2-index) };
				} else {
					msg.append("' starts with an unterminated comment");
				}
			} else if (selection.endsWith("*/")) { // comment is after
				int end = source.lastIndexOf("/**", index+selection.length());
				if (end >=0) {
					return new int[] { index, index-end };
				} else {
					msg.append("' ends with an unstartted comment");
				}
			} else { // no comment => use whole selection
				return new int[] { index, selection.length() };
			}
		} else {
			msg.append("' was not found in ");
		}
		msg.append(cu.getElementName());
		msg.append(":\n");
		msg.append(source);
		assertTrue(msg.toString(), false);
		return null;
	}

	/**
	 * Select a field in a compilation unit identified with the first occurence in the source of a given selection.
	 * @param unit
	 * @param selection
	 * @return IField
	 * @throws JavaModelException
	 */
	protected IField selectField(ICompilationUnit unit, String selection) throws JavaModelException {
		return selectField(unit, selection, 1);
	}

	/**
	 * Select a field in a compilation unit identified with the nth occurence in the source of a given selection.
	 * @param unit
	 * @param selection
	 * @param occurences
	 * @return IField
	 * @throws JavaModelException
	 */
	protected IField selectField(ICompilationUnit unit, String selection, int occurences) throws JavaModelException {
		return (IField) selectJavaElement(unit, selection, occurences, IJavaElement.FIELD);
	}

	/**
	 * Select a local variable in a compilation unit identified with the first occurence in the source of a given selection.
	 * @param unit
	 * @param selection
	 * @return IType
	 * @throws JavaModelException
	 */
	protected ILocalVariable selectLocalVariable(ICompilationUnit unit, String selection) throws JavaModelException {
		return selectLocalVariable(unit, selection, 1);
	}

	/**
	 * Select a local variable in a compilation unit identified with the nth occurence in the source of a given selection.
	 * @param unit
	 * @param selection
	 * @param occurences
	 * @return IType
	 * @throws JavaModelException
	 */
	protected ILocalVariable selectLocalVariable(ICompilationUnit unit, String selection, int occurences) throws JavaModelException {
		return (ILocalVariable) selectJavaElement(unit, selection, occurences, IJavaElement.LOCAL_VARIABLE);
	}

	/**
	 * Select a method in a compilation unit identified with the first occurence in the source of a given selection.
	 * @param unit
	 * @param selection
	 * @return IMethod
	 * @throws JavaModelException
	 */
	protected IMethod selectMethod(ICompilationUnit unit, String selection) throws JavaModelException {
		return selectMethod(unit, selection, 1);
	}

	/**
	 * Select a method in a compilation unit identified with the nth occurence in the source of a given selection.
	 * @param unit
	 * @param selection
	 * @param occurences
	 * @return IMethod
	 * @throws JavaModelException
	 */
	protected IMethod selectMethod(ICompilationUnit unit, String selection, int occurences) throws JavaModelException {
		return (IMethod) selectJavaElement(unit, selection, occurences, IJavaElement.METHOD);
	}

	/**
	 * Select a parameterized source method in a compilation unit identified with the first occurence in the source of a given selection.
	 * @param unit
	 * @param selection
	 * @return ParameterizedSourceMethod
	 * @throws JavaModelException
	 */
	protected ResolvedSourceMethod selectParameterizedMethod(ICompilationUnit unit, String selection) throws JavaModelException {
		return selectParameterizedMethod(unit, selection, 1);
	}

	/**
	 * Select a parameterized source method in a compilation unit identified with the nth occurence in the source of a given selection.
	 * @param unit
	 * @param selection
	 * @param occurences
	 * @return ParameterizedSourceMethod
	 * @throws JavaModelException
	 */
	protected ResolvedSourceMethod selectParameterizedMethod(ICompilationUnit unit, String selection, int occurences) throws JavaModelException {
		IMethod type = selectMethod(unit, selection, occurences);
		assertTrue("Not a parameterized source type: "+type.getElementName(), type instanceof ResolvedSourceMethod);
		return (ResolvedSourceMethod) type;
	}

	/**
	 * Select a parameterized source type in a compilation unit identified with the first occurence in the source of a given selection.
	 * @param unit
	 * @param selection
	 * @return ParameterizedSourceType
	 * @throws JavaModelException
	 */
	protected ResolvedSourceType selectParameterizedType(ICompilationUnit unit, String selection) throws JavaModelException {
		return selectParameterizedType(unit, selection, 1);
	}

	/**
	 * Select a parameterized source type in a compilation unit identified with the nth occurence in the source of a given selection.
	 * @param unit
	 * @param selection
	 * @param occurences
	 * @return ParameterizedSourceType
	 * @throws JavaModelException
	 */
	protected ResolvedSourceType selectParameterizedType(ICompilationUnit unit, String selection, int occurences) throws JavaModelException {
		IType type = selectType(unit, selection, occurences);
		assertTrue("Not a parameterized source type: "+type.getElementName(), type instanceof ResolvedSourceType);
		return (ResolvedSourceType) type;
	}

	/**
	 * Select a type in a compilation unit identified with the first occurence in the source of a given selection.
	 * @param unit
	 * @param selection
	 * @return IType
	 * @throws JavaModelException
	 */
	protected IType selectType(ICompilationUnit unit, String selection) throws JavaModelException {
		return selectType(unit, selection, 1);
	}

	/**
	 * Select a type in a compilation unit identified with the nth occurence in the source of a given selection.
	 * @param unit
	 * @param selection
	 * @param occurences
	 * @return IType
	 * @throws JavaModelException
	 */
	protected IType selectType(ICompilationUnit unit, String selection, int occurences) throws JavaModelException {
		return (IType) selectJavaElement(unit, selection, occurences, IJavaElement.TYPE);
	}

	/**
	 * Select a type parameter in a compilation unit identified with the first occurence in the source of a given selection.
	 * @param unit
	 * @param selection
	 * @return IType
	 * @throws JavaModelException
	 */
	protected ITypeParameter selectTypeParameter(ICompilationUnit unit, String selection) throws JavaModelException {
		return selectTypeParameter(unit, selection, 1);
	}

	/**
	 * Select a type parameter in a compilation unit identified with the nth occurence in the source of a given selection.
	 * @param unit
	 * @param selection
	 * @param occurences
	 * @return IType
	 * @throws JavaModelException
	 */
	protected ITypeParameter selectTypeParameter(ICompilationUnit unit, String selection, int occurences) throws JavaModelException {
		return (ITypeParameter) selectJavaElement(unit, selection, occurences, IJavaElement.TYPE_PARAMETER);
	}

	/**
	 * Select a java element in a compilation unit identified with the nth occurence in the source of a given selection.
	 * Do not allow subclasses to call this method as we want to verify IJavaElement kind.
	 */
	IJavaElement selectJavaElement(ICompilationUnit unit, String selection, int occurences, int elementType) throws JavaModelException {
		int[] selectionPositions = selectionInfo(unit, selection, occurences);
		IJavaElement[] elements = null;
		if (this.wcOwner == null) {
			elements = unit.codeSelect(selectionPositions[0], selectionPositions[1]);
		} else {
			elements = unit.codeSelect(selectionPositions[0], selectionPositions[1], this.wcOwner);
		}
		assertEquals("Invalid selection number", 1, elements.length);
		assertEquals("Invalid java element type: "+elements[0].getElementName(), elements[0].getElementType(), elementType);
		return elements[0];
	}

	/* ************
	 * Suite set-ups *
	 *************/
	/**
	 * Sets the class path of the Java project.
	 */
	public void setClasspath(IJavaProject javaProject, IClasspathEntry[] classpath) {
		try {
			javaProject.setRawClasspath(classpath, null);
		} catch (JavaModelException e) {
			e.printStackTrace();
			assertTrue("failed to set classpath", false);
		}
	}
	/**
	 * Check locally for the required JCL files, <jclName>.jar and <jclName>src.zip.
	 * If not available, copy from the project resources.
	 */
	public void setupExternalJCL(String jclName) throws IOException {
		String externalPath = getExternalPath();
		String separator = java.io.File.separator;
		String resourceJCLDir = getPluginDirectoryPath() + separator + "JCL";
		java.io.File jclDir = new java.io.File(externalPath);
		java.io.File jclMin =
			new java.io.File(externalPath + jclName + ".jar");
		java.io.File jclMinsrc = new java.io.File(externalPath + jclName + "src.zip");
		if (!jclDir.exists()) {
			if (!jclDir.mkdir()) {
				//mkdir failed
				throw new IOException("Could not create the directory " + jclDir);
			}
			//copy the two files to the JCL directory
			java.io.File resourceJCLMin =
				new java.io.File(resourceJCLDir + separator + jclName + ".jar");
			copy(resourceJCLMin, jclMin);
			java.io.File resourceJCLMinsrc =
				new java.io.File(resourceJCLDir + separator + jclName + "src.zip");
			copy(resourceJCLMinsrc, jclMinsrc);
		} else {
			//check that the two files, jclMin.jar and jclMinsrc.zip are present
			//copy either file that is missing or less recent than the one in workspace
			java.io.File resourceJCLMin =
				new java.io.File(resourceJCLDir + separator + jclName + ".jar");
			if ((jclMin.lastModified() < resourceJCLMin.lastModified())
                    || (jclMin.length() != resourceJCLMin.length())) {
				copy(resourceJCLMin, jclMin);
			}
			java.io.File resourceJCLMinsrc =
				new java.io.File(resourceJCLDir + separator + jclName + "src.zip");
			if ((jclMinsrc.lastModified() < resourceJCLMinsrc.lastModified())
                    || (jclMinsrc.length() != resourceJCLMinsrc.length())) {
				copy(resourceJCLMinsrc, jclMinsrc);
			}
		}
	}
	protected IJavaProject setUpJavaProject(final String projectName) throws CoreException, IOException {
		this.currentProject = setUpJavaProject(projectName, "1.4");
		return this.currentProject;
	}
	protected IJavaProject setUpJavaProject(final String projectName, String compliance) throws CoreException, IOException {
		// copy files in project from source workspace to target workspace
		String sourceWorkspacePath = getSourceWorkspacePath();
		String targetWorkspacePath = getWorkspaceRoot().getLocation().toFile().getCanonicalPath();
		copyDirectory(new File(sourceWorkspacePath, projectName), new File(targetWorkspacePath, projectName));

		// ensure variables are set
		setUpJCLClasspathVariables(compliance);

		// create project
		final IProject project = getWorkspaceRoot().getProject(projectName);
		IWorkspaceRunnable populate = new IWorkspaceRunnable() {
			public void run(IProgressMonitor monitor) throws CoreException {
				project.create(null);
				project.open(null);
			}
		};
		getWorkspace().run(populate, null);
		IJavaProject javaProject = JavaCore.create(project);
		setUpProjectCompliance(javaProject, compliance);
		javaProject.setOption(JavaCore.COMPILER_PB_UNUSED_LOCAL, JavaCore.IGNORE);
		javaProject.setOption(JavaCore.COMPILER_PB_UNUSED_PRIVATE_MEMBER, JavaCore.IGNORE);
		javaProject.setOption(JavaCore.COMPILER_PB_FIELD_HIDING, JavaCore.IGNORE);
		javaProject.setOption(JavaCore.COMPILER_PB_LOCAL_VARIABLE_HIDING, JavaCore.IGNORE);
		javaProject.setOption(JavaCore.COMPILER_PB_TYPE_PARAMETER_HIDING, JavaCore.IGNORE);
		return javaProject;
	}

	protected void setUpProjectCompliance(IJavaProject javaProject, String compliance) throws JavaModelException, IOException {
		// Look for version to set and return if that's already done
		String version = CompilerOptions.VERSION_1_4;
		String jclLibString = null;
		String newJclLibString = null;
		String newJclSrcString = null;
		switch (compliance.charAt(2)) {
			case '6':
				version = CompilerOptions.VERSION_1_6;
				if (version.equals(javaProject.getOption(CompilerOptions.OPTION_Compliance, false))) {
					return;
				}
				jclLibString = "JCL_LIB";
				newJclLibString = "JCL15_LIB";
				newJclSrcString = "JCL15_SRC";
				break;
			case '5':
				version = CompilerOptions.VERSION_1_5;
				if (version.equals(javaProject.getOption(CompilerOptions.OPTION_Compliance, false))) {
					return;
				}
				jclLibString = "JCL_LIB";
				newJclLibString = "JCL15_LIB";
				newJclSrcString = "JCL15_SRC";
				break;
			case '3':
				version = CompilerOptions.VERSION_1_3;
			default:
				if (version.equals(javaProject.getOption(CompilerOptions.OPTION_Compliance, false))) {
					return;
				}
				jclLibString = "JCL15_LIB";
				newJclLibString = "JCL_LIB";
				newJclSrcString = "JCL_SRC";
				break;
		}

		// ensure variables are set
		setUpJCLClasspathVariables(compliance);

		// set options
		Map options = new HashMap();
		options.put(CompilerOptions.OPTION_Compliance, version);
		options.put(CompilerOptions.OPTION_Source, version);
		options.put(CompilerOptions.OPTION_TargetPlatform, version);
		javaProject.setOptions(options);

		// replace JCL_LIB with JCL15_LIB, and JCL_SRC with JCL15_SRC
		IClasspathEntry[] classpath = javaProject.getRawClasspath();
		IPath jclLib = new Path(jclLibString);
		for (int i = 0, length = classpath.length; i < length; i++) {
			IClasspathEntry entry = classpath[i];
			if (entry.getPath().equals(jclLib)) {
				classpath[i] = JavaCore.newVariableEntry(
						new Path(newJclLibString),
						new Path(newJclSrcString),
						entry.getSourceAttachmentRootPath(),
						entry.getAccessRules(),
						new IClasspathAttribute[0],
						entry.isExported());
				break;
			}
		}
		javaProject.setRawClasspath(classpath, null);
	}
	public void setUpJCLClasspathVariables(String compliance) throws JavaModelException, IOException {
		if ("1.5".equals(compliance)) {
			if (JavaCore.getClasspathVariable("JCL15_LIB") == null) {
				setupExternalJCL("jclMin1.5");
				JavaCore.setClasspathVariables(
					new String[] {"JCL15_LIB", "JCL15_SRC", "JCL_SRCROOT"},
					new IPath[] {getExternalJCLPath(compliance), getExternalJCLSourcePath(compliance), getExternalJCLRootSourcePath()},
					null);
			}
		} else {
			if (JavaCore.getClasspathVariable("JCL_LIB") == null) {
				setupExternalJCL("jclMin");
				JavaCore.setClasspathVariables(
					new String[] {"JCL_LIB", "JCL_SRC", "JCL_SRCROOT"},
					new IPath[] {getExternalJCLPath(), getExternalJCLSourcePath(), getExternalJCLRootSourcePath()},
					null);
			}
		}
	}
	public void setUpSuite() throws Exception {
		super.setUpSuite();

		// ensure autobuilding is turned off
		IWorkspaceDescription description = getWorkspace().getDescription();
		if (description.isAutoBuilding()) {
			description.setAutoBuilding(false);
			getWorkspace().setDescription(description);
		}
	}
	protected void setUp () throws Exception {
		super.setUp();

		if (NameLookup.VERBOSE || BasicSearchEngine.VERBOSE || JavaModelManager.VERBOSE) {
			System.out.println("--------------------------------------------------------------------------------");
			System.out.println("Running test "+getName()+"...");
		}
	}
	protected void sortElements(IJavaElement[] elements) {
		Util.Comparer comparer = new Util.Comparer() {
			public int compare(Object a, Object b) {
				JavaElement elementA = (JavaElement)a;
				JavaElement elementB = (JavaElement)b;
				char[] tempJCLPath = "<externalJCLPath>".toCharArray();
	    		String idA = new String(CharOperation.replace(
	    			elementA.toStringWithAncestors().toCharArray(),
	    			getExternalJCLPathString().toCharArray(),
	    			tempJCLPath));
	    		String idB = new String(CharOperation.replace(
	    			elementB.toStringWithAncestors().toCharArray(),
	    			getExternalJCLPathString().toCharArray(),
	    			tempJCLPath));
				return idA.compareTo(idB);
			}
		};
		Util.sort(elements, comparer);
	}
	protected void sortResources(Object[] resources) {
		Util.Comparer comparer = new Util.Comparer() {
			public int compare(Object a, Object b) {
				if (a instanceof IResource) {
					IResource resourceA = (IResource)a;
					IResource resourceB = (IResource)b;
					return resourceA.getFullPath().toString().compareTo(resourceB.getFullPath().toString());
				} else {
					IJarEntryResource resourceA = (IJarEntryResource)a;
					IJarEntryResource resourceB = (IJarEntryResource)b;
					return resourceA.getFullPath().toString().compareTo(resourceB.getFullPath().toString());
				}
			}
		};
		Util.sort(resources, comparer);
	}
	protected void sortTypes(IType[] types) {
		Util.Comparer comparer = new Util.Comparer() {
			public int compare(Object a, Object b) {
				IType typeA = (IType)a;
				IType typeB = (IType)b;
				return typeA.getFullyQualifiedName().compareTo(typeB.getFullyQualifiedName());
			}
		};
		Util.sort(types, comparer);
	}
	/*
	 * Simulate a save/exit of the workspace
	 */
	protected void simulateExit() throws CoreException {
		waitForAutoBuild();
		getWorkspace().save(true/*full save*/, null/*no progress*/);
		JavaModelManager.getJavaModelManager().shutdown();
	}
	/*
	 * Simulate a save/exit/restart of the workspace
	 */
	protected void simulateExitRestart() throws CoreException {
		simulateExit();
		simulateRestart();
	}
	/*
	 * Simulate a restart of the workspace
	 */
	protected void simulateRestart() throws CoreException {
		JavaModelManager.doNotUse(); // reset the MANAGER singleton
		JavaModelManager.getJavaModelManager().startup();
		new JavaCorePreferenceInitializer().initializeDefaultPreferences();
	}
	/**
	 * Starts listening to element deltas, and queues them in fgDeltas.
	 */
	public void startDeltas() {
		clearDeltas();
		JavaCore.addElementChangedListener(this.deltaListener);
	}
	/**
	 * Stops listening to element deltas, and clears the current deltas.
	 */
	public void stopDeltas() {
		JavaCore.removeElementChangedListener(this.deltaListener);
		clearDeltas();
	}
	protected void startLogListening() {
		ILog log = JavaCore.getPlugin().getLog();
		if (this.logListener != null) {
			log.removeLogListener(this.logListener);
		}
		this.logListener = new ILogListener(){
			private StringBuffer buffer = new StringBuffer();
			public void logging(IStatus status, String plugin) {
				this.buffer.append(status);
				this.buffer.append('\n');
			}
			public String toString() {
				return this.buffer.toString();
			}
		};
		log.addLogListener(this.logListener);
	}
	protected void stopLogListening() {
		if (this.logListener == null)
			return;
		ILog log = JavaCore.getPlugin().getLog();
		log.removeLogListener(this.logListener);
		this.logListener = null;
	}
	protected void assertLogEquals(String expected) {
		String actual = this.logListener == null ? "<null>" : this.logListener.toString();
		assertSourceEquals(
			"Unexpected entry in log",
			expected,
			actual);
	}
	protected IPath[] toIPathArray(String[] paths) {
		if (paths == null) return null;
		int length = paths.length;
		IPath[] result = new IPath[length];
		for (int i = 0; i < length; i++) {
			result[i] = new Path(paths[i]);
		}
		return result;
	}
	protected void touch(File f) {
		int time = 1000;
		f.setLastModified(f.lastModified() + time);
		waitAtLeast(time);
	}

	protected synchronized void waitAtLeast(int time) {
		long start = System.currentTimeMillis();
		do {
			try {
				wait(time);
			} catch (InterruptedException e) {
			}
		} while ((System.currentTimeMillis() - start) < time);
	}

	protected String toString(String[] strings) {
		return toString(strings, false/*don't add extra new line*/);
	}
	protected String toString(String[] strings, boolean addExtraNewLine) {
		if (strings == null) return "null";
		StringBuffer buffer = new StringBuffer();
		for (int i = 0, length = strings.length; i < length; i++){
			buffer.append(strings[i]);
			if (addExtraNewLine || i < length - 1)
				buffer.append("\n");
		}
		return buffer.toString();
	}
	protected void tearDown() throws Exception {
		super.tearDown();
		if (this.workingCopies != null) {
			discardWorkingCopies(this.workingCopies);
			this.workingCopies = null;
		}
		this.wcOwner = null;

		// ensure workspace options have been restored to their default
		Hashtable options = JavaCore.getOptions();
		Hashtable defaultOptions = JavaCore.getDefaultOptions();
		assertEquals(
			"Workspace options should be back to their default",
			new CompilerOptions(defaultOptions).toString(),
			new CompilerOptions(options).toString());
	}
	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.tests.model.SuiteOfTestCases#tearDownSuite()
	 */
	public void tearDownSuite() throws Exception {
		super.tearDownSuite();
	}

	/**
	 * Wait for autobuild notification to occur
	 */
	public static void waitForAutoBuild() {
		boolean wasInterrupted = false;
		do {
			try {
				Job.getJobManager().join(ResourcesPlugin.FAMILY_AUTO_BUILD, null);
				wasInterrupted = false;
			} catch (OperationCanceledException e) {
				e.printStackTrace();
			} catch (InterruptedException e) {
				wasInterrupted = true;
			}
		} while (wasInterrupted);
	}

	public static void waitForManualRefresh() {
		boolean wasInterrupted = false;
		do {
			try {
				Job.getJobManager().join(ResourcesPlugin.FAMILY_MANUAL_REFRESH, null);
				wasInterrupted = false;
			} catch (OperationCanceledException e) {
				e.printStackTrace();
			} catch (InterruptedException e) {
				wasInterrupted = true;
			}
		} while (wasInterrupted);
	}

	public static void waitUntilIndexesReady() {
		// dummy query for waiting until the indexes are ready
		SearchEngine engine = new SearchEngine();
		IJavaSearchScope scope = SearchEngine.createWorkspaceScope();
		try {
			engine.searchAllTypeNames(
				null,
				SearchPattern.R_EXACT_MATCH,
				"!@$#!@".toCharArray(),
				SearchPattern.R_PATTERN_MATCH | SearchPattern.R_CASE_SENSITIVE,
				IJavaSearchConstants.CLASS,
				scope,
				new TypeNameRequestor() {
					public void acceptType(
						int modifiers,
						char[] packageName,
						char[] simpleTypeName,
						char[][] enclosingTypeNames,
						String path) {}
				},
				IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH,
				null);
		} catch (CoreException e) {
		}
	}
}