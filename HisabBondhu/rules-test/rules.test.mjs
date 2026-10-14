// Run from the project root's rules-test folder:  npm install && npm test
// Starts the Firestore emulator, loads ../firestore.rules, and tries to break the isolation with two accounts.
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, setDoc, getDoc, deleteDoc, getDocs, collection } from 'firebase/firestore';
import { readFileSync } from 'node:fs';

const env = await initializeTestEnvironment({
  projectId: 'demo-hisab',
  firestore: { rules: readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8') },
});
let pass = 0, fail = 0;
async function t(name, fn) { try { await fn(); pass++; console.log('PASS', name); } catch (e) { fail++; console.log('FAIL', name, '\n   ', e.message); } }
const now = Date.now();
const alice = env.authenticatedContext('alice').firestore();
const bob = env.authenticatedContext('bob').firestore();
const anon = env.unauthenticatedContext().firestore();
const cust = (uid, id = 'c1') => ({ id, ownerUid: uid, name: 'রহিম', updatedAt: now });
const sale = (uid, id = 's1', extra = {}) => ({ id, ownerUid: uid, total: 1000, paid: 400, status: 'ACTIVE', updatedAt: now, items: [], ...extra });

await t('owner can create own customer', () => assertSucceeds(setDoc(doc(alice, 'users/alice/customers/c1'), cust('alice'))));
await t('owner can read own customer', () => assertSucceeds(getDoc(doc(alice, 'users/alice/customers/c1'))));
await t('owner can list own collection', () => assertSucceeds(getDocs(collection(alice, 'users/alice/customers'))));
await t('other account cannot read', () => assertFails(getDoc(doc(bob, 'users/alice/customers/c1'))));
await t('other account cannot list', () => assertFails(getDocs(collection(bob, 'users/alice/customers'))));
await t('other account cannot write', () => assertFails(setDoc(doc(bob, 'users/alice/customers/c2'), cust('alice', 'c2'))));
await t('other account cannot overwrite', () => assertFails(setDoc(doc(bob, 'users/alice/customers/c1'), { ...cust('alice'), name: 'hacked' })));
await t('other account cannot delete', () => assertFails(deleteDoc(doc(bob, 'users/alice/customers/c1'))));
await t('signed-out cannot read', () => assertFails(getDoc(doc(anon, 'users/alice/customers/c1'))));
await t('signed-out cannot write', () => assertFails(setDoc(doc(anon, 'users/alice/customers/c9'), cust('alice', 'c9'))));
await t('cannot write own path with someone else ownerUid', () => assertFails(setDoc(doc(bob, 'users/bob/customers/c1'), cust('alice'))));
await t('cannot change ownerUid on update', () => assertFails(setDoc(doc(alice, 'users/alice/customers/c1'), cust('bob'))));
await t('doc id must match path id', () => assertFails(setDoc(doc(alice, 'users/alice/customers/c3'), cust('alice', 'other'))));
await t('owner cannot delete (no hard deletes)', () => assertFails(deleteDoc(doc(alice, 'users/alice/customers/c1'))));
await t('unknown collection is rejected', () => assertFails(setDoc(doc(alice, 'users/alice/secrets/x'), cust('alice', 'x'))));
await t('bob has his own space', () => assertSucceeds(setDoc(doc(bob, 'users/bob/customers/c1'), cust('bob'))));
await t('alice cannot read bob', () => assertFails(getDoc(doc(alice, 'users/bob/customers/c1'))));
await t('root user doc: other account denied', () => assertFails(getDoc(doc(bob, 'users/alice'))));
await t('root user doc: owner ok', () => assertSucceeds(setDoc(doc(alice, 'users/alice'), { schema: 1, ownerUid: 'alice' })));

await t('sale create ok', () => assertSucceeds(setDoc(doc(alice, 'users/alice/sales/s1'), sale('alice'))));
await t('sale paid > total rejected', () => assertFails(setDoc(doc(alice, 'users/alice/sales/s2'), sale('alice', 's2', { paid: 5000 }))));
await t('sale total cannot be rewritten', () => assertFails(setDoc(doc(alice, 'users/alice/sales/s1'), sale('alice', 's1', { total: 1 }))));
await t('sale can be cancelled', () => assertSucceeds(setDoc(doc(alice, 'users/alice/sales/s1'), sale('alice', 's1', { status: 'CANCELLED', cancelReason: 'ভুল', updatedAt: now + 1 }))));
await t('cancelled sale cannot be re-activated', () => assertFails(setDoc(doc(alice, 'users/alice/sales/s1'), sale('alice', 's1', { status: 'ACTIVE', cancelReason: 'ভুল', updatedAt: now + 2 }))));

const pay = (uid, extra = {}) => ({ id: 'p1', ownerUid: uid, amount: 500, updatedAt: now, ...extra });
await t('payment create ok', () => assertSucceeds(setDoc(doc(alice, 'users/alice/payments/p1'), pay('alice'))));
await t('payment identical retry ok (idempotent)', () => assertSucceeds(setDoc(doc(alice, 'users/alice/payments/p1'), pay('alice'))));
await t('payment amount cannot be edited', () => assertFails(setDoc(doc(alice, 'users/alice/payments/p1'), pay('alice', { amount: 1 }))));
await t('stock movement cannot be edited', async () => {
  await setDoc(doc(alice, 'users/alice/stockMovements/m1'), { id: 'm1', ownerUid: 'alice', qtyMilli: 1000, updatedAt: now });
  await assertFails(setDoc(doc(alice, 'users/alice/stockMovements/m1'), { id: 'm1', ownerUid: 'alice', qtyMilli: 9000, updatedAt: now }));
});

console.log(`\n${pass} passed, ${fail} failed`);
await env.cleanup();
process.exit(fail ? 1 : 0);
