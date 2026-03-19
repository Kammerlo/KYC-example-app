///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21
//DESCRIPTION API probe
//DEPS com.bloxbean.cardano:cardano-client-lib:0.7.1
//DEPS com.bloxbean.cardano:cardano-client-backend-blockfrost:0.7.1
//DEPS com.bloxbean.cardano:cardano-client-quicktx:0.7.1

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.common.model.Networks;
import java.math.BigInteger;
import java.util.Arrays;

public class ProbeApi {
    public static void main(String[] args) throws Exception {
        // Check what generateMnemonic returns
        String mnemonic = Account.generateMnemonic();
        System.out.println("Generated mnemonic: [" + mnemonic + "]");
        System.out.println("Word count: " + mnemonic.split("\\s+").length);

        // Try creating account with it
        try {
            var acc = new Account(Networks.testnet(), mnemonic);
            System.out.println("Account created OK: " + acc.baseAddress());
            var pkh = acc.hdKeyPair().getPublicKey().getKeyHash();
            System.out.println("PKH type: " + pkh.getClass().getSimpleName() + ", len=" + pkh.length);
        } catch (Exception e) {
            System.out.println("Account creation FAILED: " + e.getMessage());
        }

        // Check ScriptTx methods needed
        var st = new ScriptTx();
        System.out.println("\nScriptTx payToContract:");
        Arrays.stream(st.getClass().getMethods())
              .filter(m -> m.getName().equals("payToContract"))
              .forEach(m -> System.out.println("  " + m.getName() + " " + Arrays.toString(m.getParameterTypes())));

        System.out.println("\nTx payToAddress single Amount:");
        var tx = new Tx();
        Arrays.stream(tx.getClass().getMethods())
              .filter(m -> m.getName().equals("payToAddress"))
              .forEach(m -> System.out.println("  " + Arrays.toString(m.getParameterTypes())));
    }
}
