package dev.bcrick.secondo.cli;

/**
 * Test subclass of SecondoCli that returns a FakeRpcClient instead of a real one.
 */
class TestableSecondoCli extends SecondoCli {

    private final FakeRpcClient fakeClient = new FakeRpcClient();

    @Override
    RpcClient createClient() {
        return fakeClient;
    }

    FakeRpcClient getFakeClient() {
        return fakeClient;
    }
}
