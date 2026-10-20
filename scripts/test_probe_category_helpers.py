import unittest
from probe_category_helpers import flatten_categories, select_seller_samples

class ProbeSamplingTests(unittest.TestCase):
    def test_each_query_reserves_two_new_sellers(self):
        first = [{"id": str(1000+i), "seller_id": str(i)} for i in range(10)]
        samples = select_seller_samples(first, [])
        self.assertEqual(2, len(samples))
        later = first + [{"id": "9999", "seller_id": "99"}]
        following = select_seller_samples(later, samples)
        self.assertEqual(2, len(following))
        self.assertFalse(set(s for _, s in samples) & set(s for _, s in following))

    def test_invalid_identifiers_are_not_requested(self):
        self.assertEqual([], select_seller_samples(
            [None, {"id":"../1", "seller_id":"1"}, {"id":"1234","seller_id":"abc"}], []))

    def test_nested_subcategories_keep_own_label_and_duplicate_ids(self):
        nodes = flatten_categories([{"id":1, "name":"Steam", "sub":[
            {"id":7,"name":"Steam Gifts"},
            {"id":7,"name":"Accounts","sub":[{"id":8,"name":"Other"}]}]}])
        self.assertEqual(["Steam","Steam Gifts","Accounts","Other"], [x["name"] for x in nodes])
        self.assertEqual(2, sum(x["id"] == 7 for x in nodes))
