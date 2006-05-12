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
package org.eclipse.ltk.ui.refactoring.tests;

import org.eclipse.ui.plugin.AbstractUIPlugin;

public class RefactoringUITestPlugin extends AbstractUIPlugin {
	
	private static RefactoringUITestPlugin fgDefault;
	
	public RefactoringUITestPlugin() {
		fgDefault= this;
	}

	public static RefactoringUITestPlugin getDefault() {
		return fgDefault;
	}
	
	public static String getPluginId() {
		return "org.eclipse.ltk.ui.refactoring.tests"; //$NON-NLS-1$
	}	
}
