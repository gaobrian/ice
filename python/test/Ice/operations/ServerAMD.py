#!/usr/bin/env python
# **********************************************************************
#
# Copyright (c) 2003-2018 ZeroC, Inc. All rights reserved.
#
# This copy of Ice is licensed to you under the terms described in the
# ICE_LICENSE file included in this distribution.
#
# **********************************************************************

import sys
#
# We want to test coroutines but older versions of Python cannot
# load a source file that uses the async/await keywords, so we
# have two versions of MyDerivedClassI.
#
if sys.version_info >= (3, 5):
    from TestAMDCoroI import MyDerivedClassI
else:
    from TestAMDI import MyDerivedClassI


from TestHelper import TestHelper
TestHelper.loadSlice("Test.ice")
import Ice


class ServerAMD(TestHelper):

    def run(self, args):
        properties = self.createTestProperties(args)
        #
        # Its possible to have batch oneway requests dispatched after the
        # adapter is deactivated due to thread scheduling so we supress
        # this warning.
        #
        properties.setProperty("Ice.Warn.Dispatch", "0")

        with self.initialize(properties=properties) as communicator:
            communicator.getProperties().setProperty("TestAdapter.Endpoints", self.getTestEndpoint())
            adapter = communicator.createObjectAdapter("TestAdapter")
            adapter.add(MyDerivedClassI(), Ice.stringToIdentity("test"))
            adapter.activate()
            communicator.waitForShutdown()
