package io.muleshield.core.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class HashChainTest {

    @Test
    void detectsAnEditedEntryAndEverythingAfterIt() {
        List<HashChain.Link> chain = new ArrayList<>();
        HashChain.Link last = null;
        for (String event : List.of("{\"hold\":\"placed\"}", "{\"notice\":\"sent\"}", "{\"hold\":\"released\"}")) {
            last = HashChain.append(last, event);
            chain.add(last);
        }
        assertThat(HashChain.firstBroken(chain)).isEmpty();

        HashChain.Link edited = chain.get(1);
        chain.set(1, new HashChain.Link(edited.seq(), edited.prevHash(), "{\"notice\":\"never sent\"}", edited.hash()));
        assertThat(HashChain.firstBroken(chain)).hasValue(2);
    }

    @Test
    void detectsADeletedEntry() {
        HashChain.Link a = HashChain.append(null, "a");
        HashChain.Link b = HashChain.append(a, "b");
        HashChain.Link c = HashChain.append(b, "c");
        assertThat(HashChain.firstBroken(List.of(a, c))).hasValue(3);
    }
}
