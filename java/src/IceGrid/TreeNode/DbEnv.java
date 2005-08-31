// **********************************************************************
//
// Copyright (c) 2003-2005 ZeroC, Inc. All rights reserved.
//
// This copy of Ice is licensed to you under the terms described in the
// ICE_LICENSE file included in this distribution.
//
// **********************************************************************
package IceGrid.TreeNode;

import IceGrid.DbEnvDescriptor;
import IceGrid.Model;
import IceGrid.Utils;

class DbEnv extends Leaf
{
    DbEnv(String dbEnvName, DbEnvDescriptor descriptor, 
	  Utils.Resolver resolver, Model model)
    {
	super(dbEnvName, model);
	_descriptor = descriptor;
	_resolver = resolver;
    }

    private DbEnvDescriptor _descriptor;
    private Utils.Resolver _resolver;
}
