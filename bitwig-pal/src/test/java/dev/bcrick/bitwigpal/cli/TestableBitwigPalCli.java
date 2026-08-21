package dev.bcrick.bitwigpal.cli;

/**
 * Test subclass of BitwigPalCli that returns a FakeRpcClient instead of a real one.
 */
class TestableBitwigPalCli extends BitwigPalCli {

    private final FakeRpcClient fakeClient = new FakeRpcClient();

    @Override
    RpcClient createClient() {
        return fakeClient;
    }

    FakeRpcClient getFakeClient() {
        return fakeClient;
    }
}
