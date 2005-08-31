// **********************************************************************
//
// Copyright (c) 2003-2005 ZeroC, Inc. All rights reserved.
//
// This copy of Ice is licensed to you under the terms described in the
// ICE_LICENSE file included in this distribution.
//
// **********************************************************************
package IceGrid.TreeNode;

import IceGrid.TemplateDescriptor;
import IceGrid.Model;

class ServiceTemplate extends EditableParent
{
    ServiceTemplate(boolean brandNew, String name, 
		    TemplateDescriptor descriptor, Model model)
	throws DuplicateIdException
    {
	super(brandNew, name, model);
	rebuild(descriptor);
    }
    
    ServiceTemplate(ServiceTemplate o)
    {
	super(o, true);
	_templateDescriptor = o._templateDescriptor;
	_adapters = o._adapters;
	_dbEnvs = o._dbEnvs;
	_propertiesHolder = new PropertiesHolder(_templateDescriptor.descriptor, this);
    }


    void rebuild(TemplateDescriptor descriptor)
	throws DuplicateIdException
    {
	_templateDescriptor = descriptor;
	_propertiesHolder = new PropertiesHolder(_templateDescriptor.descriptor, this);
	clearChildren();

	//
	// Fix-up parameters order
	//
	java.util.Collections.sort(_templateDescriptor.parameters);
	
	_adapters = new Adapters(_templateDescriptor.descriptor.adapters, true, 
				 null, null, _model);
	addChild(_adapters);

	_dbEnvs = new DbEnvs(_templateDescriptor.descriptor.dbEnvs, true,
			     null, _model);
	addChild(_dbEnvs);
    }

    public PropertiesHolder getPropertiesHolder()
    {
	return _propertiesHolder;
    }

    public String toString()
    {
	return templateLabel(_id, _templateDescriptor.parameters);
    }

    private TemplateDescriptor _templateDescriptor;
    private Adapters _adapters;
    private DbEnvs _dbEnvs;

    private PropertiesHolder _propertiesHolder;
}
