// **********************************************************************
//
// Copyright (c) 2003-2005 ZeroC, Inc. All rights reserved.
//
// This copy of Ice is licensed to you under the terms described in the
// ICE_LICENSE file included in this distribution.
//
// **********************************************************************

public class JADAttribute
{
    public void
    setName(String name)
    {
        _name = name;
    }

    public String
    getName()
    {
        return _name;
    }

    public void
    setValue(String value)
    {
        _value = value;
    }

    public String
    getValue()
    {
        return _value;
    }

    private String _name;
    private String _value;
}

