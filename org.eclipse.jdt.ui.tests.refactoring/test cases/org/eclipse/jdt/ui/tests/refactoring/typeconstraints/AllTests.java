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
package org.eclipse.jdt.ui.tests.refactoring.typeconstraints;


import junit.framework.Test;
import junit.framework.TestSuite;


public class AllTests {

	public static Test suite ( ) {
		TestSuite suite= new TestSuite("All Type constraints Tests");
		suite.addTest(TypeConstraintTests.suite());
		suite.addTest(TypeEnvironmentTests.suite());
	    return suite;
	}
}

