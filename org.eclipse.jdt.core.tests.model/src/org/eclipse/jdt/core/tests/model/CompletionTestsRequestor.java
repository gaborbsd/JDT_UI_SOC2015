/*******************************************************************************
 * Copyright (c) 2000, 2006 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.core.tests.model;

import java.util.Vector;

import org.eclipse.jdt.core.CompletionProposal;
import org.eclipse.jdt.core.CompletionRequestor;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.Signature;

public class CompletionTestsRequestor extends CompletionRequestor {
	private Vector elements = new Vector();
	private Vector completions = new Vector();
	private Vector relevances = new Vector();
	private Vector completionStart = new Vector();
	private Vector completionEnd = new Vector();
	
	public boolean debug = false;

	private void acceptCommon(CompletionProposal proposal) {
		completions.addElement(new String(proposal.getCompletion()));
		relevances.addElement(String.valueOf(proposal.getRelevance()));
		completionStart.addElement(String.valueOf(proposal.getReplaceStart()));
		completionEnd.addElement(String.valueOf(proposal.getReplaceEnd()));
	}
	public void accept(CompletionProposal proposal) {
		char[] typeName = null;
		switch(proposal.getKind()) {
			case CompletionProposal.ANONYMOUS_CLASS_DECLARATION :
				typeName = Signature.getSignatureSimpleName(proposal.getDeclarationSignature());
				elements.addElement(new String(typeName));
				this.acceptCommon(proposal);
				if (debug)
					System.out.println("anonymous type " + new String(typeName));
				break;
				
			case CompletionProposal.TYPE_REF :
				if((proposal.getFlags() & Flags.AccEnum) != 0) {
					
				} else if((proposal.getFlags() & Flags.AccInterface) != 0) {
					typeName = Signature.getSignatureSimpleName(proposal.getSignature());
					elements.addElement(new String(typeName));
					this.acceptCommon(proposal);
					if (debug)
						System.out.println("Interface " + new String(typeName));
				} else {
					typeName = Signature.getSignatureSimpleName(proposal.getSignature());
					elements.addElement(new String(typeName));
					this.acceptCommon(proposal);
					if (debug) {
						if(Signature.getTypeSignatureKind(proposal.getSignature()) == Signature.TYPE_VARIABLE_SIGNATURE) {
							System.out.println("type parameter " + new String(typeName));
						} else {
							System.out.println("Class " + new String(typeName));
						}
					}
				}
				break;
				
			case CompletionProposal.FIELD_REF :
				elements.addElement(new String(proposal.getName()));
				this.acceptCommon(proposal);
				if (debug)
					System.out.println("Field " + new String(proposal.getName()));
				break;
				
			case CompletionProposal.KEYWORD:
				elements.addElement(new String(proposal.getName()));
				this.acceptCommon(proposal);
				if (debug)
					System.out.println("Keyword " + new String(proposal.getName()));
				break;
				
			case CompletionProposal.LABEL_REF:
				elements.addElement(new String(proposal.getName()));
				this.acceptCommon(proposal);
				if (debug)
					System.out.println("Label " + new String(proposal.getName()));
				break;
				
			case CompletionProposal.LOCAL_VARIABLE_REF:
				elements.addElement(new String(proposal.getName()));
				this.acceptCommon(proposal);
				if (debug)
					System.out.println("Local variable " + new String(proposal.getName()));
				break;
				
			case CompletionProposal.METHOD_REF:
				elements.addElement(new String(proposal.getName()));
				this.acceptCommon(proposal);
				if (debug)
					System.out.println("method " + new String(proposal.getName()));
				break;
				
			case CompletionProposal.METHOD_DECLARATION:
				elements.addElement(new String(proposal.getName()));
				this.acceptCommon(proposal);
				if (debug)
					System.out.println("method declaration " + new String(proposal.getName()));
				break;
				
			case CompletionProposal.PACKAGE_REF:
				elements.addElement(new String(proposal.getDeclarationSignature()));
				this.acceptCommon(proposal);
				if (debug)
					System.out.println("package " + new String(proposal.getDeclarationSignature()));
				break;
				
			case CompletionProposal.VARIABLE_DECLARATION:
				elements.addElement(new String(proposal.getName()));
				this.acceptCommon(proposal);
				if (debug)
					System.out.println("variable name " + new String(proposal.getName()));
				break;
		}

	}

	public String getResults() {
		return getResults(true, false);
	}

	public String getResultsWithPosition(){
		return getResults(true, true);
	}

	public String getResults(boolean relevance, boolean position) {
		StringBuffer result = new StringBuffer();
		int size = elements.size();
		
		if (size == 1) {
			result.append(getResult(0, relevance, position));
		} else if (size > 1) {
			String[] sortedBucket = new String[size];
			for (int i = 0; i < size; i++) {
				sortedBucket[i] = getResult(i, relevance, position);
			}
			quickSort(sortedBucket, 0, size - 1);
			for (int j = 0; j < sortedBucket.length; j++) {
				if (result.length() > 0) result.append("\n");
				result.append(sortedBucket[j]);
			}
		}

		return result.toString();
	}

	private String getResult(int i, boolean relevance, boolean position) {
		if(i < 0 || i >= elements.size())
			return "";
		
		StringBuffer buffer =  new StringBuffer();
		buffer.append("element:");
		buffer.append(elements.elementAt(i));
		buffer.append("    completion:");
		buffer.append(completions.elementAt(i));
		if(position) {
			buffer.append("    position:[");
			buffer.append(completionStart.elementAt(i));
			buffer.append(",");
			buffer.append(completionEnd.elementAt(i));
			buffer.append("]");
		}
		if(relevance) {
			buffer.append("    relevance:");
			buffer.append(relevances.elementAt(i));
		}
		return buffer.toString();
	}

	protected String[] quickSort(String[] collection, int left, int right) {
		int original_left = left;
		int original_right = right;
		String mid = collection[ (left + right) / 2];
		do {
			while (mid.compareTo(collection[left]) > 0)
				// s[left] >= mid
				left++;
			while (mid.compareTo(collection[right]) < 0)
				// s[right] <= mid
				right--;
			if (left <= right) {
				String tmp = collection[left];
				collection[left] = collection[right];
				collection[right] = tmp;
				left++;
				right--;
			}
		} while (left <= right);
		if (original_left < right)
			collection = quickSort(collection, original_left, right);
		if (left < original_right)
			collection = quickSort(collection, left, original_right);
		return collection;
	}
	public String toString() {
		return getResults();
	}
}
