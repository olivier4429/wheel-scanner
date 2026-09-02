package com.wheelscanner.data;

import com.wheelscanner.model.Opportunity;

import java.util.Collections;
import java.util.List;

/**
 * Placeholder pour la future implémentation Interactive Brokers.
 *
 * Quand tu seras prêt :
 * 1. Ajoute la dépendance TWS API
 * 2. Implémente la connexion (IbConnection)
 * 3. Remplace le corps de findOpportunities()
 * 4. Dans Main.java change simplement :
 *    DataProvider provider = new IbkrDataProvider(...);
 */
public class IbkrDataProvider implements DataProvider {

    // private final IbConnection connection;

    // public IbkrDataProvider(IbConnection connection) {
    //     this.connection = connection;
    // }

    @Override
    public List<Opportunity> findOpportunities(String ticker) {
        // TODO: Implémenter avec l'API TWS / Gateway
        System.out.println("IbkrDataProvider non encore implémenté pour " + ticker);
        return Collections.emptyList();
    }
}
