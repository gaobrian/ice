// **********************************************************************
//
// Copyright (c) 2003-2018 ZeroC, Inc. All rights reserved.
//
// This copy of Ice is licensed to you under the terms described in the
// ICE_LICENSE file included in this distribution.
//
// **********************************************************************

#pragma once

[["cs:namespace:Ice.hold"]]
module Test
{

interface Hold
{
    void putOnHold(int seconds);
    void waitForHold();
    int set(int value, int delay);
    void setOneway(int value, int expected);
    void shutdown();
}

}
